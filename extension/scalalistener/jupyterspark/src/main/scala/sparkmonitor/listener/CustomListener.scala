package sparkmonitor.listener

import org.apache.spark.scheduler._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.apache.spark._
import org.apache.spark.TaskEndReason
import org.apache.spark.JobExecutionStatus
import org.apache.spark.SparkContext
import sparkmonitor.listener.UIData._
import scala.collection.mutable
import scala.collection.mutable.{ HashMap, HashSet, LinkedHashMap, ListBuffer }
import java.net._
import java.io._

/**
 * A SparkListener Implementation that forwards data to a Jupyter Kernel
 *
 *  All data is forwarded to a jupyter kernel using sockets configured by an environemnt variable.
 *  @constructor called by Spark internally
 *  @param conf Spark configuration object used to start the spark application
 */
class JupyterSparkMonitorListener(conf: SparkConf) extends SparkListener {

	println("SPARKLISTENER: Started SparkListener for Jupyter Notebook")
	val port = scala.util.Properties.envOrElse("SPARKMONITOR_KERNEL_PORT", "ERRORNOTFOUND")
	println("SPARKLISTENER: Port obtained from environment: " + port)
	var socket: Socket = null
	var out: OutputStreamWriter = null
	try {
		socket = new Socket("localhost", port.toInt)
		out = new OutputStreamWriter(socket.getOutputStream())
	} catch {
		case exception: Throwable => println("\nSPARKLISTENER: Exception creating socket:" + exception + "\n")
	}

	/**Send a string message to the kernel using the open socket.*/
	def send(msg: String): Unit = {
		try {
			//println("\nSPARKLISTENER: --------------Sending Message:------------------\n"+msg+
			//	"\nSPARKLISTENER: -------------------------------------------------\n")
			out.write(msg + ";EOD:")
			out.flush()
		} catch {
			case exception: Throwable => println("\nSPARKLISTENER: Exception sending socket message:" + exception + "\n")
		}
	}

	/**Close the socket connection*/
	def closeConnection(): Unit = {
		println("SPARKLISTNER: Closing Connection")
		out.close()
		socket.close()
	}


	type JobId = Int
	type JobGroupId = String
	type StageId = Int
	type StageAttemptId = Int

	//Application
	@volatile var startTime = -1L
	@volatile var endTime = -1L
	var appId: String = ""

	//Jobs
	val activeJobs = new HashMap[JobId, JobUIData]
	val completedJobs = ListBuffer[JobUIData]()
	val failedJobs = ListBuffer[JobUIData]()
	val jobIdToData = new HashMap[JobId, JobUIData]
	val jobGroupToJobIds = new HashMap[JobGroupId, HashSet[JobId]]

	// Stages:
	val pendingStages = new HashMap[StageId, StageInfo]
	val activeStages = new HashMap[StageId, StageInfo]
	val completedStages = ListBuffer[StageInfo]()
	val skippedStages = ListBuffer[StageInfo]()
	val failedStages = ListBuffer[StageInfo]()
	val stageIdToData = new HashMap[(StageId, StageAttemptId), StageUIData]
	val stageIdToInfo = new HashMap[StageId, StageInfo]
	val stageIdToActiveJobIds = new HashMap[StageId, HashSet[JobId]]

	var numCompletedStages = 0
	var numFailedStages = 0
	var numCompletedJobs = 0
	var numFailedJobs = 0

	val retainedStages = conf.getInt("spark.ui.retainedStages", 1000)
	val retainedJobs = conf.getInt("spark.ui.retainedJobs", 1000)
	val retainedTasks = 100000

	@volatile
	var totalNumActiveTasks = 0
	val executorCores = new HashMap[String, Int]
	@volatile var totalCores: Int = 0
	@volatile var numExecutors: Int = 0

	override def onApplicationStart(appStarted: SparkListenerApplicationStart): Unit = {
		startTime = appStarted.time
		appId = appStarted.appId.getOrElse("null")
		println("SPARKLISTENER Application Started: " + appId + " ...Start Time: " + appStarted.time)

		val json = ("msgtype" -> "sparkApplicationStart") ~
			("startTime" -> startTime) ~
			("appId" -> appId) ~
			("appAttemptId" -> appStarted.appAttemptId.getOrElse("null")) ~
			("appName" -> appStarted.appName) ~
			("sparkUser" -> appStarted.sparkUser)

		send(pretty(render(json)))
	}

	override def onApplicationEnd(appEnded: SparkListenerApplicationEnd): Unit = {
		println("SPARKLISTENER Application ending...End Time: " + appEnded.time)
		endTime = appEnded.time
		val json = ("msgtype" -> "sparkApplicationEnd") ~
			("endTime" -> endTime)

		send(pretty(render(json)))
		closeConnection()
	}

	def stageInfoToJSON(stageInfo: StageInfo): JObject = {
		val completionTime: Long = stageInfo.completionTime.getOrElse(-1)
		val submissionTime: Long = stageInfo.submissionTime.getOrElse(-1)

		(stageInfo.stageId.toString ->
			("attemptId" -> stageInfo.attemptId) ~
			("name" -> stageInfo.name) ~
			("numTasks" -> stageInfo.numTasks) ~
			("completionTime" -> completionTime) ~
			("submissionTime" -> submissionTime))
	}

	override def onJobStart(jobStart: SparkListenerJobStart): Unit = synchronized {

		val jobGroup = for (
			props <- Option(jobStart.properties);
			group <- Option(props.getProperty("spark.jobGroup.id"))
		) yield group

		val jobData: JobUIData =
			new JobUIData(
				jobId = jobStart.jobId,
				submissionTime = Option(jobStart.time).filter(_ >= 0),
				stageIds = jobStart.stageIds,
				jobGroup = jobGroup,
				status = JobExecutionStatus.RUNNING)

		jobGroupToJobIds.getOrElseUpdate(jobGroup.orNull, new HashSet[JobId]).add(jobStart.jobId)
		jobStart.stageInfos.foreach(x => pendingStages(x.stageId) = x)

		var stageinfojson: JObject = Nil
		for (x <- jobStart.stageInfos) {
			stageinfojson = stageinfojson ~ stageInfoToJSON(x)
		}

		jobData.numTasks = {
			val allStages = jobStart.stageInfos
			val missingStages = allStages.filter(_.completionTime.isEmpty)
			missingStages.map(_.numTasks).sum
		}
		jobIdToData(jobStart.jobId) = jobData
		activeJobs(jobStart.jobId) = jobData
		for (stageId <- jobStart.stageIds) {
			stageIdToActiveJobIds.getOrElseUpdate(stageId, new HashSet[StageId]).add(jobStart.jobId)
		}

		// If there's no information for a stage, store the StageInfo received from the scheduler
		// so that we can display stage descriptions for pending stages:
		for (stageInfo <- jobStart.stageInfos) {
			stageIdToInfo.getOrElseUpdate(stageInfo.stageId, stageInfo)
			stageIdToData.getOrElseUpdate((stageInfo.stageId, stageInfo.attemptId), new StageUIData)
		}

		val name = jobStart.properties.getProperty("callSite.short", "null")
		println("Num Executors" + numExecutors.toInt)
		val json = ("msgtype" -> "sparkJobStart") ~
			("jobGroup" -> jobGroup.getOrElse("null")) ~
			("jobId" -> jobStart.jobId) ~
			("status" -> "RUNNING") ~
			("submissionTime" -> Option(jobStart.time).filter(_ >= 0)) ~
			("stageIds" -> jobStart.stageIds) ~
			("stageInfos" -> stageinfojson) ~
			("numTasks" -> jobData.numTasks) ~
			("totalCores" -> totalCores) ~
			("appId" -> appId) ~
			("numExecutors" -> numExecutors) ~
			("name" -> name)
		println("SPARKLISTENER JobStart: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = synchronized {
		val jobData = activeJobs.remove(jobEnd.jobId).getOrElse {
			println("SPARKLISTENER:Job completed for unknown job: " + jobEnd.jobId)
			new JobUIData(jobId = jobEnd.jobId)
		}
		jobData.completionTime = Option(jobEnd.time).filter(_ >= 0)
		var status = "null"
		jobData.stageIds.foreach(pendingStages.remove)
		jobEnd.jobResult match {
			case JobSucceeded =>
				completedJobs += jobData
				trimJobsIfNecessary(completedJobs)
				jobData.status = JobExecutionStatus.SUCCEEDED
				status = "SUCCEEDED"
				numCompletedJobs += 1
			case _ =>
				failedJobs += jobData
				trimJobsIfNecessary(failedJobs)
				jobData.status = JobExecutionStatus.FAILED
				numFailedJobs += 1
				status = "FAILED"
		}

		for (stageId <- jobData.stageIds) {
			stageIdToActiveJobIds.get(stageId).foreach { jobsUsingStage =>
				jobsUsingStage.remove(jobEnd.jobId)
				if (jobsUsingStage.isEmpty) {
					stageIdToActiveJobIds.remove(stageId)
				}
				stageIdToInfo.get(stageId).foreach { stageInfo =>
					if (stageInfo.submissionTime.isEmpty) {
						// if this stage is pending, it won't complete, so mark it as "skipped":
						skippedStages += stageInfo
						trimStagesIfNecessary(skippedStages)
						jobData.numSkippedStages += 1
						jobData.numSkippedTasks += stageInfo.numTasks
					}
				}
			}
		}

		val json = ("msgtype" -> "sparkJobEnd") ~
			("jobId" -> jobEnd.jobId) ~
			("status" -> status) ~
			("completionTime" -> jobData.completionTime)

		//println("SPARKLISTENER Job End: \n"+ pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = synchronized {
		val stage = stageCompleted.stageInfo
		stageIdToInfo(stage.stageId) = stage
		val stageData = stageIdToData.getOrElseUpdate((stage.stageId, stage.attemptId), {
			println("SPARKLISTENER: Stage completed for unknown stage " + stage.stageId)
			new StageUIData
		})

		var status = "UNKNOWN"
		activeStages.remove(stage.stageId)
		if (stage.failureReason.isEmpty) {
			completedStages += stage
			numCompletedStages += 1
			trimStagesIfNecessary(completedStages)
			status = "COMPLETED"
		} else {
			failedStages += stage
			numFailedStages += 1
			trimStagesIfNecessary(failedStages)
			status = "FAILED"
		}
		for (
			activeJobsDependentOnStage <- stageIdToActiveJobIds.get(stage.stageId);
			jobId <- activeJobsDependentOnStage;
			jobData <- jobIdToData.get(jobId)
		) {
			jobData.numActiveStages -= 1
			if (stage.failureReason.isEmpty) {
				if (stage.submissionTime.isDefined) {
					jobData.completedStageIndices.add(stage.stageId)
				}
			} else {
				jobData.numFailedStages += 1
			}
		}

		val completionTime: Long = stage.completionTime.getOrElse(-1)
		val submissionTime: Long = stage.submissionTime.getOrElse(-1)

		val json = ("msgtype" -> "sparkStageCompleted") ~
			("stageId" -> stage.stageId) ~
			("stageAttemptId" -> stage.attemptId) ~
			("completionTime" -> completionTime) ~
			("submissionTime" -> submissionTime) ~
			("numTasks" -> stage.numTasks) ~
			("status" -> status)

		println("SPARKLISTENER Stage Completed: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = synchronized {
		val stage = stageSubmitted.stageInfo
		activeStages(stage.stageId) = stage
		pendingStages.remove(stage.stageId)

		stageIdToInfo(stage.stageId) = stage
		val stageData = stageIdToData.getOrElseUpdate((stage.stageId, stage.attemptId), new StageUIData)

		stageData.description = Option(stageSubmitted.properties).flatMap {
			p => Option(p.getProperty("spark.job.description"))
		}

		for (
			activeJobsDependentOnStage <- stageIdToActiveJobIds.get(stage.stageId);
			jobId <- activeJobsDependentOnStage;
			jobData <- jobIdToData.get(jobId)
		) {
			jobData.numActiveStages += 1
			// If a stage retries again, it should be removed from completedStageIndices set
			jobData.completedStageIndices.remove(stage.stageId)
		}

		val activeJobsDependentOnStage = stageIdToActiveJobIds.get(stage.stageId)
		val jobIds = activeJobsDependentOnStage

		val submissionTime: Long = stage.submissionTime.getOrElse(-1)
		val json = ("msgtype" -> "sparkStageSubmitted") ~
			("stageId" -> stage.stageId) ~
			("stageAttemptId" -> stage.attemptId) ~
			("name" -> stage.name) ~
			("numTasks" -> stage.numTasks) ~
			//  ("details" -> stage.details) ~
			("parentIds" -> stage.parentIds) ~
			("submissionTime" -> submissionTime) ~
			("jobIds" -> jobIds)

		println("SPARKLISTENER Stage Submitted: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = synchronized {
		val taskInfo = taskStart.taskInfo
		if (taskInfo != null) {
			val stageData = stageIdToData.getOrElseUpdate((taskStart.stageId, taskStart.stageAttemptId), {
				println("SPARKLISTENER: Task start for unknown stage " + taskStart.stageId)
				new StageUIData
			})
			stageData.numActiveTasks += 1
		}
		var jobjson = ("jobdata" -> "taskstart")
		for (
			activeJobsDependentOnStage <- stageIdToActiveJobIds.get(taskStart.stageId);
			jobId <- activeJobsDependentOnStage;
			jobData <- jobIdToData.get(jobId)
		) {

			jobData.numActiveTasks += 1
			val jobjson = ("jobdata" ->
				("jobId" -> jobData.jobId) ~
				("numTasks" -> jobData.numTasks) ~
				("numActiveTasks" -> jobData.numActiveTasks) ~
				("numCompletedTasks" -> jobData.numCompletedTasks) ~
				("numSkippedTasks" -> jobData.numSkippedTasks) ~
				("numFailedTasks" -> jobData.numFailedTasks) ~
				("reasonToNumKilled" -> jobData.reasonToNumKilled) ~
				("numActiveStages" -> jobData.numActiveStages) ~
				("numSkippedStages" -> jobData.numSkippedStages) ~
				("numFailedStages" -> jobData.numFailedStages))

		}

		val json = ("msgtype" -> "sparkTaskStart") ~
			("launchTime" -> taskInfo.launchTime) ~
			("taskId" -> taskInfo.taskId) ~
			("stageId" -> taskStart.stageId) ~
			("stageAttemptId" -> taskStart.stageAttemptId) ~
			("index" -> taskInfo.index) ~
			("attemptNumber" -> taskInfo.attemptNumber) ~
			("executorId" -> taskInfo.executorId) ~
			("host" -> taskInfo.host) ~
			("status" -> taskInfo.status) ~
			("speculative" -> taskInfo.speculative)

		//println("SPARKLISTENER Task Started: \n"+ pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = synchronized {
		val info = taskEnd.taskInfo
		// If stage attempt id is -1, it means the DAGScheduler had no idea which attempt this task
		// completion event is for. Let's just drop it here. This means we might have some speculation
		// tasks on the web ui that's never marked as complete.
		var errorMessage: Option[String] = None
		if (info != null && taskEnd.stageAttemptId != -1) {
			val stageData = stageIdToData.getOrElseUpdate((taskEnd.stageId, taskEnd.stageAttemptId), {
				println("SPARKLISTENER: Task end for unknown stage " + taskEnd.stageId)
				new StageUIData
			})

			stageData.numActiveTasks -= 1
			errorMessage = taskEnd.reason match {
				case org.apache.spark.Success =>
					stageData.completedIndices.add(info.index)
					stageData.numCompleteTasks += 1
					None
				// The class sparkTaskKilled is marked private by the package, so not accessible
				//case kill: org.apache.spark.TaskKilled =>
				//	stageData.reasonToNumKilled = stageData.reasonToNumKilled.updated(
				//	kill.reason, stageData.reasonToNumKilled.getOrElse(kill.reason, 0) + 1)
				//	Some(kill.toErrorString)
				case e: ExceptionFailure => // Handle ExceptionFailure because we might have accumUpdates
					stageData.numFailedTasks += 1
					Some(e.toErrorString)
				case e: TaskFailedReason => // All other failure cases
					stageData.numFailedTasks += 1
					Some(e.toErrorString)
			}

			for (
				activeJobsDependentOnStage <- stageIdToActiveJobIds.get(taskEnd.stageId);
				jobId <- activeJobsDependentOnStage;
				jobData <- jobIdToData.get(jobId)
			) {
				jobData.numActiveTasks -= 1
				taskEnd.reason match {
					case Success =>
						jobData.numCompletedTasks += 1
					//case kill: TaskKilled =>
					//	jobData.reasonToNumKilled = jobData.reasonToNumKilled.updated(
					//	kill.reason, jobData.reasonToNumKilled.getOrElse(kill.reason, 0) + 1)
					case _ =>
						jobData.numFailedTasks += 1
				}
			}
		}

		var jsonmetrics: JObject = ("" -> "")
		val totalExecutionTime = info.finishTime - info.launchTime
		def toProportion(time: Long) = time.toDouble / totalExecutionTime * 100

		var metricsOpt = Option(taskEnd.taskMetrics)
		val shuffleReadTime = metricsOpt.map(_.shuffleReadMetrics.fetchWaitTime).getOrElse(0L)
		val shuffleReadTimeProportion = toProportion(shuffleReadTime)
		val shuffleWriteTime = (metricsOpt.map(_.shuffleWriteMetrics.writeTime).getOrElse(0L) / 1e6).toLong
		val shuffleWriteTimeProportion = toProportion(shuffleWriteTime)
		val serializationTime = metricsOpt.map(_.resultSerializationTime).getOrElse(0L)
		val serializationTimeProportion = toProportion(serializationTime)
		val deserializationTime = metricsOpt.map(_.executorDeserializeTime).getOrElse(0L)
		val deserializationTimeProportion = toProportion(deserializationTime)
		val gettingResultTime = if (info.gettingResult) {
			if (info.finished) {
				info.finishTime - info.gettingResultTime
			} else {
				0L //currentTime - info.gettingResultTime
			}
		} else {
			0L
		}
		val gettingResultTimeProportion = toProportion(gettingResultTime)
		val executorOverhead = serializationTime + deserializationTime
		val executorRunTime = metricsOpt.map(_.executorRunTime).getOrElse(totalExecutionTime - executorOverhead - gettingResultTime)
		val schedulerDelay = math.max(0, totalExecutionTime - executorRunTime - executorOverhead - gettingResultTime)
		val schedulerDelayProportion = toProportion(schedulerDelay)
		val executorComputingTime = executorRunTime - shuffleReadTime - shuffleWriteTime
		val executorComputingTimeProportion =
			math.max(100 - schedulerDelayProportion - shuffleReadTimeProportion -
				shuffleWriteTimeProportion - serializationTimeProportion -
				deserializationTimeProportion - gettingResultTimeProportion, 0)

		val schedulerDelayProportionPos = 0
		val deserializationTimeProportionPos = schedulerDelayProportionPos + schedulerDelayProportion
		val shuffleReadTimeProportionPos = deserializationTimeProportionPos + deserializationTimeProportion
		val executorRuntimeProportionPos = shuffleReadTimeProportionPos + shuffleReadTimeProportion
		val shuffleWriteTimeProportionPos = executorRuntimeProportionPos + executorComputingTimeProportion
		val serializationTimeProportionPos = shuffleWriteTimeProportionPos + shuffleWriteTimeProportion
		val gettingResultTimeProportionPos = serializationTimeProportionPos + serializationTimeProportion

		if (!metricsOpt.isEmpty) {
			jsonmetrics = ("shuffleReadTime" -> shuffleReadTime) ~
				("shuffleWriteTime" -> shuffleWriteTime) ~
				("serializationTime" -> serializationTime) ~
				("deserializationTime" -> deserializationTime) ~
				("gettingResultTime" -> gettingResultTime) ~
				("executorComputingTime" -> executorComputingTime) ~
				("schedulerDelay" -> schedulerDelay) ~
				("shuffleReadTimeProportion" -> shuffleReadTimeProportion) ~
				("shuffleWriteTimeProportion" -> shuffleWriteTimeProportion) ~
				("serializationTimeProportion" -> serializationTimeProportion) ~
				("deserializationTimeProportion" -> deserializationTimeProportion) ~
				("gettingResultTimeProportion" -> gettingResultTimeProportion) ~
				("executorComputingTimeProportion" -> executorComputingTimeProportion) ~
				("schedulerDelayProportion" -> schedulerDelayProportion) ~
				("shuffleReadTimeProportionPos" -> shuffleReadTimeProportionPos) ~
				("shuffleWriteTimeProportionPos" -> shuffleWriteTimeProportionPos) ~
				("serializationTimeProportionPos" -> serializationTimeProportionPos) ~
				("deserializationTimeProportionPos" -> deserializationTimeProportionPos) ~
				("gettingResultTimeProportionPos" -> gettingResultTimeProportionPos) ~
				("executorComputingTimeProportionPos" -> executorRuntimeProportionPos) ~
				("schedulerDelayProportionPos" -> schedulerDelayProportionPos) ~
				("resultSize" -> metricsOpt.map(_.resultSize).getOrElse(0L)) ~
				("jvmGCTime" -> metricsOpt.map(_.jvmGCTime).getOrElse(0L)) ~
				("memoryBytesSpilled" -> metricsOpt.map(_.memoryBytesSpilled).getOrElse(0L)) ~
				("diskBytesSpilled" -> metricsOpt.map(_.diskBytesSpilled).getOrElse(0L)) ~
				("peakExecutionMemory" -> metricsOpt.map(_.peakExecutionMemory).getOrElse(0L)) ~
				("test" -> info.gettingResultTime)
		}

		val json = ("msgtype" -> "sparkTaskEnd") ~
			("launchTime" -> info.launchTime) ~
			("finishTime" -> info.finishTime) ~
			("taskId" -> info.taskId) ~
			("stageId" -> taskEnd.stageId) ~
			("taskType" -> taskEnd.taskType) ~
			("stageAttemptId" -> taskEnd.stageAttemptId) ~
			("index" -> info.index) ~
			("attemptNumber" -> info.attemptNumber) ~
			("executorId" -> info.executorId) ~
			("host" -> info.host) ~
			("status" -> info.status) ~
			("speculative" -> info.speculative) ~
			("errorMessage" -> errorMessage) ~
			("metrics" -> jsonmetrics)

		println("SPARKLISTENER Task Ended: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	/** If stages is too large, remove and garbage collect old stages */
	private def trimStagesIfNecessary(stages: ListBuffer[StageInfo]) = synchronized {
		if (stages.size > retainedStages) {
			val toRemove = calculateNumberToRemove(stages.size, retainedStages)
			stages.take(toRemove).foreach { s =>
				stageIdToData.remove((s.stageId, s.attemptId))
				stageIdToInfo.remove(s.stageId)
			}
			stages.trimStart(toRemove)
		}
	}

	/** If jobs is too large, remove and garbage collect old jobs */
	private def trimJobsIfNecessary(jobs: ListBuffer[JobUIData]) = synchronized {
		if (jobs.size > retainedJobs) {
			val toRemove = calculateNumberToRemove(jobs.size, retainedJobs)
			jobs.take(toRemove).foreach { job =>
				// Remove the job's UI data, if it exists
				jobIdToData.remove(job.jobId).foreach { removedJob =>
					// A null jobGroupId is used for jobs that are run without a job group
					val jobGroupId = removedJob.jobGroup.orNull
					// Remove the job group -> job mapping entry, if it exists
					jobGroupToJobIds.get(jobGroupId).foreach { jobsInGroup =>
						jobsInGroup.remove(job.jobId)
						// If this was the last job in this job group, remove the map entry for the job group
						if (jobsInGroup.isEmpty) {
							jobGroupToJobIds.remove(jobGroupId)
						}
					}
				}
			}
			jobs.trimStart(toRemove)
		}
	}

	private def calculateNumberToRemove(dataSize: Int, retainedSize: Int): Int = {
		math.max(retainedSize / 10, dataSize - retainedSize)
	}

	override def onExecutorAdded(executorAdded: SparkListenerExecutorAdded): Unit = synchronized {
		executorCores(executorAdded.executorId) = executorAdded.executorInfo.totalCores
		totalCores += executorAdded.executorInfo.totalCores
		numExecutors += 1

		val json = ("msgtype" -> "sparkExecutorAdded") ~
			("executorId" -> executorAdded.executorId) ~
			("time" -> executorAdded.time) ~
			("host" -> executorAdded.executorInfo.executorHost) ~
			("numCores" -> executorAdded.executorInfo.totalCores) ~
			("totalCores" -> totalCores) // Sending this as browser data can be lost during reloads

		println("SPARKLISTENER Executor Added: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}

	override def onExecutorRemoved(executorRemoved: SparkListenerExecutorRemoved): Unit = synchronized {
		totalCores -= executorCores.getOrElse(executorRemoved.executorId, 0)
		numExecutors -= 1
		val json = ("msgtype" -> "sparkExecutorRemoved") ~
			("executorId" -> executorRemoved.executorId) ~
			("time" -> executorRemoved.time) ~
			("totalCores" -> totalCores) // Sending this as browser data can be lost during reloads

		println("SPARKLISTENER Executor Removed: \n" + pretty(render(json)) + "\n")
		send(pretty(render(json)))
	}
}

//---------------------Data Structures for storing Data
object UIData {

	class JobUIData(
		var jobId: Int = -1,
		var submissionTime: Option[Long] = None,
		var completionTime: Option[Long] = None,
		var stageIds: Seq[Int] = Seq.empty,
		var jobGroup: Option[String] = None,
		var status: JobExecutionStatus = JobExecutionStatus.UNKNOWN,
		var numTasks: Int = 0,
		var numActiveTasks: Int = 0,
		var numCompletedTasks: Int = 0,
		var numSkippedTasks: Int = 0,
		var numFailedTasks: Int = 0,
		var reasonToNumKilled: Map[String, Int] = Map.empty,
		var numActiveStages: Int = 0,
		// This needs to be a set instead of a simple count to prevent double-counting of rerun stages:
		var completedStageIndices: mutable.HashSet[Int] = new mutable.HashSet[Int](),
		var numSkippedStages: Int = 0,
		var numFailedStages: Int = 0)

	class StageUIData {
		var numActiveTasks: Int = _
		var numCompleteTasks: Int = _
		var completedIndices = new HashSet[Int]()
		var numFailedTasks: Int = _
		var description: Option[String] = None
	}

	class ExecutorData {
		var numCores: Int = _
		var executorId: String = _
		var timeAdded: Long = _
		var timeRemoved: Long = _
		var executorHost: String = _
	}
}