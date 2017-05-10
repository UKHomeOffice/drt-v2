package passengersplits.polling

import java.util.Date

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import com.mfglabs.stream.SinkExt
import passengersplits.core.ZipUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.stream.Materializer
import drt.shared.{MilliDate, SDateLike}
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerInfoRouterActor.{ManifestZipFileInit, PassengerSplitsAck, VoyageManifestZipFileComplete, VoyageManifestZipFileCompleteAck}
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import passengersplits.s3._
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object ManifestFilePolling {

  class BatchCompletionMonitor(promise: Promise[String]) extends Actor with ActorLogging {
    def receive: Receive = {
      case VoyageManifestZipFileCompleteAck(zipfilename) =>
        log.info(s"$self FlightPaxSplitBatchComplete")
        promise.complete(Try(zipfilename))
      case d =>
        log.error(s"got unexpected $d")
    }

  }

  def props(promiseZipDone: Promise[String]): Props = Props(classOf[BatchCompletionMonitor], promiseZipDone)

  def zipCompletionMonitor(actorSystem: ActorSystem, props: Props) = actorSystem.actorOf(props)


  def completionMessage(zipfileName: String, batchCompletionMonitor: ActorRef): VoyageManifestZipFileComplete =
    VoyageManifestZipFileComplete(zipfileName, batchCompletionMonitor)

  def subscriberFlightActor(flightPassengerReporter: ActorRef, completionMessage: VoyageManifestZipFileComplete) =
    Sink.actorRefWithAck(flightPassengerReporter, ManifestZipFileInit, PassengerSplitsAck, completionMessage)

}


object FutureUtils {

  /*
  lift a Seq[Future[T] into a Seq[Future[Try[T]]
  why? so we can do a waitAll.

  A future.Sequence given a seq of Seq(Successin(10 seconds), FailureIn(1 second)) will return a failure - that first
   future to fail. We want all our successes and failures in this case, so we wrap the T in an explicit Try
   */
  def lift[T](futures: Seq[Future[T]]): Seq[Future[Try[T]]] =
    futures.map(_.map {
      Success(_)
    }.recover { case t => Failure(t) })

  def waitAll[T](futures: Seq[Future[T]]): Future[Seq[Try[T]]] =
    Future.sequence(lift(futures)) // having neutralized exception completions through the lifting, .sequence can now be used
}

object AtmosManifestFilePolling {
  val log = LoggerFactory.getLogger(getClass)

  val zipFileTimeout = FiniteDuration(12, SECONDS)

  import FutureUtils._

  type TickId = DateTime

  def filterToFilesNewerThan(listOfFiles: Seq[String], latestFile: String) = {
    log.info(s"filtering ${listOfFiles.length} files with $latestFile")
    val regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r
    val filterFrom = latestFile match {
      case regex(dateTime, _) => dateTime
      case _ => latestFile
    }
    listOfFiles.filter(fn => fn >= filterFrom && fn != latestFile)
  }

  def previousDayDqFilename(date: MilliDate) = {
    dqFilename(previousDay(date))
  }

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    val previousDay = SDate(date.millisSinceEpoch - oneDayInMillis)
    previousDay
  }

  def dqFilename(previousDay: SDateLike) = {
    val year = previousDay.getFullYear() - 2000
    f"drt_dq_$year${previousDay.getMonth()}%02d${previousDay.getDate()}%02d"
  }

  def voyageManifestFilter(portCode: String)(vm: VoyageManifest): Boolean = vm.ArrivalPortCode == portCode

  def beginPolling(fileProvider: FileProvider,
                   flightPassengerReporter: ActorRef,
                   initialFileFilter: String,
                   portCode: String,
                   tickingSource: Source[DateTime, Any],
                   batchAtMost: FiniteDuration)(implicit actorSystem: ActorSystem, mat: Materializer) = {

    val batchFileState = new LoggingBatchFileState {
      override var latestFileName: String = initialFileFilter
    }

    beginPollingImpl(flightPassengerReporter,
      tickingSource,
      fileProvider,
      batchFileState,
      batchAtMost,
      voyageManifestFilter(portCode))
  }


  def beginPollingImpl(flightPassengerReporter: ActorRef,
                       tickingSource: Source[DateTime, Any],
                       fileProvider: FileProvider,
                       batchFileState: LoggingBatchFileState,
                       batchAtMost: FiniteDuration,
                       voyageManifestFilter: (VoyageManifest) => Boolean)(implicit actorSystem: ActorSystem, mat: Materializer): Future[Done] = {
    tickingSource.runForeach { tickId =>
      val zipFilenamesSource: Source[String, NotUsed] = fileProvider.createFilenameSource()
      runSingleBatch(tickId, zipFilenamesSource, fileProvider.zipFilenameToEventualFileContent, flightPassengerReporter, batchFileState, batchAtMost, voyageManifestFilter)
    }
  }

  def tickingSource(initialDelay: FiniteDuration, interval: FiniteDuration): Source[DateTime, Any] = {
    Source.tick(initialDelay, interval, NotUsed).map((notUsed) => DateTime.now())
  }


  type UnzipFileContentFunc[ZippedFilename] = (ZippedFilename) => Future[List[UnzippedFileContent]]

  trait BatchFileState {
    def latestFile: String

    def onZipFileBegins(filename: String): Unit

    def onZipFileProcessed(filename: String): Unit

    def onBatchComplete(tickId: TickId): Unit
  }

  trait LoggingBatchFileState extends BatchFileState {
    var latestFileName: String

    def onZipFileBegins(filename: String): Unit =
      log.info(s"beginning processing $filename")

    def onZipFileProcessed(filename: String) = {
      log.info(s"setting latest filename ${filename}")
      latestFileName = filename
    }

    def onBatchComplete(tickId: TickId) = log.info(s"tickId: $tickId batch complete")

    def latestFile = latestFileName
  }


  def runSingleBatch(tickId: TickId, zipFilenamesSource: Source[String, NotUsed], unzipFileContent: UnzipFileContentFunc[String],
                     flightPassengerReporter: ActorRef, batchFileState: BatchFileState,
                     batchAtMost: FiniteDuration,
                     voyageManifestFilter: (VoyageManifest) => Boolean)(implicit actorSystem: ActorSystem, materializer: Materializer): Unit = {
    log.info(s"tickId: $tickId Starting batch")
    val futureZipFiles: Future[Seq[String]] = zipFilenamesSource.runWith(Sink.seq)


    futureZipFiles.onComplete {
      case oc => log.debug(s"tickId: $tickId zip file list retrieval completes with $oc")
    }

    val futureOfFutureProcessedZipFiles: Future[Seq[Future[String]]] = for (fileNames <- futureZipFiles) yield {
      processAllZipFiles(tickId, unzipFileContent, flightPassengerReporter, batchFileState, fileNames, voyageManifestFilter)
    }

    case class ZipFailure(filename: String, t: Throwable)

    val futureOfProcessedZipFiles: Future[Seq[Try[String]]] = futureOfFutureProcessedZipFiles.flatMap { (batchSuccess: Seq[Future[String]]) =>
      val allZipFilesInBatch: Future[Seq[Try[String]]] = waitAll(batchSuccess)
      allZipFilesInBatch.onComplete {
        (oc) =>
          batchFileState.onBatchComplete(tickId)
      }
      allZipFilesInBatch
    }

    val allZips = futureOfProcessedZipFiles.onComplete {
      case Success(allZipFilesInBatch) =>
        log.info(s"tickId: $tickId batch success $allZipFilesInBatch")
      case Failure(t) =>
        log.error("failure in runSingleBatch", t)
    }
    log.info(s"tickId: $tickId, awaiting completion of zip file batch")
    Await.result(futureOfProcessedZipFiles, batchAtMost) // todo don't commit this either
    log.info(s"tickId: $tickId, zip file batch completed")
  }

  def processAllZipFiles(tickId: DateTime,
                         unzipFileContent: UnzipFileContentFunc[String],
                         flightPassengerReporter: ActorRef,
                         batchFileState: BatchFileState,
                         fileNames: Seq[String],
                         voyageManifestFilter: (VoyageManifest) => Boolean)
                        (implicit actorSystem: ActorSystem, materializer: Materializer): Seq[Future[String]] = {
    val latestFile = batchFileState.latestFile
    val zipFilesToProcess: Seq[String] = filterToFilesNewerThan(fileNames, latestFile).sorted.toList
    log.info(s"tickId: ${tickId} batch begins zipFilesToProcess: ${zipFilesToProcess} since $latestFile, allFiles: ${fileNames.length} vs ${zipFilesToProcess.length}")
    zipFilesToProcess
      .map(zipFilename => {
        def logMsg(s: String) = {s"""tickId: $tickId, zip: "$zipFilename" $s"""}

        def logInfo(s: String) = log.info(logMsg(s))

        def logWarn(s: String) = log.warn(logMsg(s))

        logInfo(s"latestFile: $latestFile AdvPaxInfo: extracting manifests from zip")
        batchFileState.onZipFileBegins(zipFilename)
        //todo we should probably keep this a stream, earlier on, it could simplify some of the Future shenanigans later
        val zipFuture: Future[List[UnzippedFileContent]] = unzipFileContent(zipFilename)
        type ZipFilename = String
        type JsonFilename = String
        type FileSourceAndOptManifest = (Option[ZipFilename], JsonFilename, Try[VoyageManifest])
        val manifestFuture: Future[Seq[FileSourceAndOptManifest]] = zipFuture
          .map(manifests => {
            val jsonFilenames = manifests.map(_.filename)
            logInfo(s"processing manifests from zip. Length: ${manifests.length}, Content: ${jsonFilenames}")
            parseManifests(logInfo, flightPassengerReporter, manifests)
          })


        val promiseZipDone = Promise[ZipFilename]()
        val monitor = ManifestFilePolling.zipCompletionMonitor(actorSystem, ManifestFilePolling.props(promiseZipDone))
        log.debug(s"tickId: $tickId zipCompletionMonitor is $monitor")
        val subscriberFlightActor = ManifestFilePolling.subscriberFlightActor(flightPassengerReporter,
          VoyageManifestZipFileComplete(zipFilename, monitor))

        val eventualZipCompletion = promiseZipDone.future

        manifestFuture.onFailure {
          case failure: Throwable =>
            logWarn(s"error in batch $failure")
            promiseZipDone.failure(failure)
            batchFileState.onZipFileProcessed(zipFilename)
        }
        manifestFuture map { manifestMessages =>
          logInfo(s"got ${manifestMessages.length} manifest messages to send")

          val manifestSource: Source[(Option[ZipFilename], JsonFilename, Try[VoyageManifest]), NotUsed] = Source(manifestMessages)
          val successfullyParsed: Source[VoyageManifest, NotUsed] = manifestSource mapConcat {
            case (_, jsonFile, Failure(f)) =>
              logWarn(s"""Failed to parse voyage passenger info: "$jsonFile", error: $f""")
              Nil
            case (_, _, Success(vpi: VoyageManifest)) => vpi :: Nil
          }
          val justMyPort = successfullyParsed.filter(voyageManifestFilter)
          val relevantManifests = justMyPort.collect {
            case vm if vm.EventCode == VoyageManifestParser.EventCodes.CheckIn => vm
          }

          val zipSendingGraph: RunnableGraph[NotUsed] = relevantManifests.to(subscriberFlightActor)
          zipSendingGraph.run()

          eventualZipCompletion.onFailure { case oc => log.error(logMsg(s"processingZip had error ${oc}")) }
          eventualZipCompletion.onSuccess {
            case zipFilename =>
              batchFileState.onZipFileProcessed(zipFilename)
              logInfo(s"finished processing. latestFile now: ${batchFileState.latestFile}")
          }
        }
        Await.ready(eventualZipCompletion, zipFileTimeout)

        eventualZipCompletion
      })
  }

  // todo rather than this List[ZipUtils.UnzippedFileContent] we should be trying to deal in Sources, if we do
  // that should enable us to reduce some of the Future state management
  private def parseManifests(logInfo: (String) => Unit, advPaxReporter: ActorRef, manifests: List[ZipUtils.UnzippedFileContent]): Seq[(Option[String], String, Try[VoyageManifest])]
  = {
    manifests.map((flightManifest) => {
      logInfo(s"AdvPaxInfo: parsing manifest ${flightManifest.filename} from ${flightManifest.zipFilename}")
      (flightManifest.zipFilename, flightManifest.filename, VoyagePassengerInfoParser.parseVoyagePassengerInfo(flightManifest.content))
    })
  }
}
