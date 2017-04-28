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
import passengersplits.core.PassengerInfoRouterActor.{FlightPaxSplitBatchComplete, FlightPaxSplitBatchCompleteAck, FlightPaxSplitBatchInit, PassengerSplitsAck}
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import passengersplits.s3._
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object Polling {

  class BatchCompletionMonitor(promise: Promise[String]) extends Actor with ActorLogging {
    def receive: Receive = {
      case FlightPaxSplitBatchCompleteAck(zipfilename) =>
        log.info(s"$self FlightPaxSplitBatchComplete")
        promise.complete(Try(zipfilename))
      case d =>
        log.error(s"got unexpected $d")
    }

  }

  def props(promiseZipDone: Promise[String]): Props = Props(classOf[BatchCompletionMonitor], promiseZipDone)

  def completionMonitor(actorSystem: ActorSystem, props: Props) = actorSystem.actorOf(props)


  def completionMessage(zipfileName: String, batchCompletionMonitor: ActorRef): FlightPaxSplitBatchComplete =
    FlightPaxSplitBatchComplete(zipfileName, batchCompletionMonitor)

  def subscriberFlightActor(flightPassengerReporter: ActorRef, completionMessage: FlightPaxSplitBatchComplete) =
    Sink.actorRefWithAck(flightPassengerReporter, FlightPaxSplitBatchInit, PassengerSplitsAck, completionMessage)

}

object FilePolling {
  def beginPolling(log: LoggingAdapter, flightPassengerReporter: ActorRef, zipFilePath: String,
                   initialFileFilter: Option[String], portCode: String)(implicit actorSystem: ActorSystem, mat: Materializer): Future[String] = {
    val statefulPoller: StatefulLocalFileSystemPoller = StatefulLocalFileSystemPoller(initialFileFilter, zipFilePath)
    val unzippedFileProvider: SimpleLocalFileSystemReader = statefulPoller.unzippedFileProvider
    val onNewFileSeen = statefulPoller.onNewFileSeen

    val promiseZipDone = Promise[String]()
    val batchPollProcessingDone = promiseZipDone.future


    val unzipFlow = Flow[String]
      .mapAsync(1)(unzippedFileProvider.zipFilenameToEventualFileContent(_))
      .mapConcat(unzippedFileContents => unzippedFileContents.map(uzfc => VoyagePassengerInfoParser.parseVoyagePassengerInfo(uzfc.content)))
      .collect {
        case Success(vpi) if vpi.ArrivalPortCode == portCode => vpi
      }.map(uzfc => {
      log.info(s"VoyagePaxSplits ${uzfc.summary}")
      uzfc
    })

    val subscriberFlightActor = Polling.subscriberFlightActor(flightPassengerReporter,
      Polling.completionMessage("not a zip", Polling.completionMonitor(actorSystem, Polling.props(promiseZipDone))))

    val unzippedSink = unzipFlow.to(subscriberFlightActor)
    val i = 1

    val runOnce = UnzipGraphStage.runOnce(log)(unzippedFileProvider.latestFilePaths) _

    val onBatchReadingFinished = (tryDone: Try[Done]) => log.info(s"Reading files finished")

    runOnce(i, onBatchReadingFinished, onNewFileSeen, unzippedSink)

    batchPollProcessingDone.onComplete {
      case Success(complete) =>
        log.info(s"FilePolling complete $complete")
      case Failure(f) =>
        log.error(f, s"FilePolling failed $f")

    }
    batchPollProcessingDone
  }
}


object FutureUtils {
  private def lift[T](futures: Seq[Future[T]]) =
    futures.map(_.map { Success(_) }.recover { case t => Failure(t) })

  def waitAll[T](futures: Seq[Future[T]]) =
    Future.sequence(lift(futures)) // having neutralized exception completions through the lifting, .sequence can now be used
}

object AtmosFilePolling {
  val log = LoggerFactory.getLogger(getClass)
  import FutureUtils._

  type TickId = DateTime

  def filterToFilesNewerThan(listOfFiles: Seq[String], latestFile: String) = {
    log.info(s"filtering ${listOfFiles.length} with $latestFile")
    val regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r
    val filterFrom = latestFile match {
      case regex(dateTime, _) => dateTime
      case _ => latestFile
    }
    println(s"filterFrom: $filterFrom, latestFile: $latestFile")
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

  def beginPolling(log: LoggingAdapter,
                   flightPassengerReporter: ActorRef,
                   initialFileFilter: String,
                   atmosHost: String,
                   bucket: String, portCode: String,
                   tickingSource: Source[DateTime, Any],
                   batchAtMost: FiniteDuration = 90 seconds)(implicit actorSystem: ActorSystem, mat: Materializer) = {
    val statefulPoller = StatefulAtmosPoller(Some(initialFileFilter), atmosHost, bucket)
    val unzippedFileProvider = statefulPoller.unzippedFileProvider


    def createFilenameSource() = {
      unzippedFileProvider.createBuilder.listFilesAsStream(bucket).map(_._1)
    }


    def getUzfc(zipFileName: String): Future[List[UnzippedFileContent]] = {
      manifestsFromZip(unzippedFileProvider, mat, zipFileName)
    }

    val batchFileState = new LogginBatchFileState {
      override var latestFileName: String = initialFileFilter
    }

    beginPollingImpl(flightPassengerReporter, tickingSource, createFilenameSource _, getUzfc _, batchFileState, batchAtMost)
  }


  def beginPollingImpl(flightPassengerReporter: ActorRef,
                       tickingSource: Source[DateTime, Any],
                       createFilenameSource: () => Source[String, NotUsed],
                       unzippedFilenameContent: UnzipFileContentFunc[String],
                       batchFileState: LogginBatchFileState,
                       batchAtMost: FiniteDuration)(implicit actorSystem: ActorSystem, mat: Materializer): Future[Done] = {
    tickingSource.runForeach { tickId =>
      val zipfilenamesSource: Source[String, NotUsed] = createFilenameSource()
      runSingleBatch(tickId, zipfilenamesSource, unzippedFilenameContent, flightPassengerReporter, batchFileState, batchAtMost)
    }
  }

  def tickingSource(initialDelay: FiniteDuration, interval: FiniteDuration): Source[DateTime, Any] = {
    Source.tick(initialDelay, interval, NotUsed).map((notUsed) => DateTime.now())
  }


  type UnzipFileContentFunc[ZippedFileName] = (ZippedFileName) => Future[List[UnzippedFileContent]]

  trait BatchFileState {
    def latestFile: String

    def onZipFileProcessed(filename: String): Unit

    def onBatchComplete(tickId: TickId): Unit
  }

  trait LogginBatchFileState extends BatchFileState {
    var latestFileName: String

    def onZipFileProcessed(filename: String) = {
      log.info(s"setting latest filename ${filename}")
      latestFileName = filename
    }

    def onBatchComplete(tickId: TickId) = log.info(s"tickId: $tickId batch complete")

    def latestFile = latestFileName
  }


  def runSingleBatch(tickId: TickId, zipFilenamesSource: Source[String, NotUsed], unzipFileContent: UnzipFileContentFunc[String],
                     flightPassengerReporter: ActorRef, batchFileState: BatchFileState,
                     batchAtMost: FiniteDuration)(implicit actorSystem: ActorSystem, materializer: Materializer): Unit = {
    log.info(s"tickId: $tickId Starting batch")
    val futureZipFiles: Future[Seq[String]] = zipFilenamesSource.runWith(Sink.seq)
    val internalMaterializer = materializer //..ActorMaterializer()

    val futureOfFutureProcessedZipFiles: Future[Seq[Future[String]]] = for (fileNames <- futureZipFiles) yield {
      processSingleZipFile(tickId, unzipFileContent, flightPassengerReporter, batchFileState, fileNames)
    }

    case class ZipFailure(filename: String, t: Throwable)

    val futureOfProcessedZipFiles: Future[Seq[Try[String]]] = futureOfFutureProcessedZipFiles.flatMap { (batchSuccess: Seq[Future[String]]) =>
      val allZipFilesInBatch: Future[Seq[Try[String]]] = waitAll(batchSuccess)
      allZipFilesInBatch.onComplete{
        (oc) => log.info(s"tickId: $tickId batchComplete")
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
    log.info(s"tickId: $tickId, awaiting zip file completions")
    Await.result(futureOfProcessedZipFiles, batchAtMost) // todo don't commit this either
    log.info(s"tickId: $tickId, got zip file completions")
  }

  def processSingleZipFile(tickId: DateTime, unzipFileContent: UnzipFileContentFunc[String], flightPassengerReporter: ActorRef, batchFileState: BatchFileState, fileNames: Seq[String])
                          (implicit actorSystem: ActorSystem, materializer: Materializer): Seq[Future[String]] = {
    val latestFile = batchFileState.latestFile
    val zipFilesToProcess: Seq[String] = filterToFilesNewerThan(fileNames, latestFile).sorted.toList
    log.info(s"tickId: ${tickId} batch begins zipFilesToProcess: ${zipFilesToProcess} since $latestFile, allFiles: ${fileNames.length} vs ${zipFilesToProcess.length}")
    zipFilesToProcess
      .map(zipFileName => {
        log.info(s"tickId: $tickId: latestFile: $latestFile")
        log.info(s"tickId: $tickId: AdvPaxInfo: extracting manifests from zip $zipFileName")

        val zipFuture: Future[List[UnzippedFileContent]] = unzipFileContent(zipFileName)
        val messagesToSendFuture: Future[Seq[VoyagePassengerInfo]] = zipFuture
          .map(manifests => {
            log.info(s"tickId: $tickId processing manifests from zip '$zipFileName'. Length: ${manifests.length}, Content: ${manifests.map(_.filename)}")
            val voyagePassengerInfos = manifestsToAdvPaxReporter(log, flightPassengerReporter, manifests)
            log.info(s"got voyagePassengerifnso ${voyagePassengerInfos}")
            val (vpis, errors) = voyagePassengerInfos partition {
              case (_, _, Success(s)) => true
              case _ => false
            }
            log.info(s"vpis $vpis")
            log.info(s"errors $errors")
            errors.foreach {
              case (zipfile, jsonfile, Failure(f)) => log.warn(s"Failed to parse voyage passenger info: '${jsonfile}' in ${zipfile}, error: $f")
            }


            log.info(s"tickId: $tickId processed manifests from zip '$zipFileName': Length ${manifests.length}  ${manifests.headOption.map(_.filename)}")
            vpis.map { case (zipfile, jsonfile, Success(vpi)) => vpi }

          })

        val promiseZipDone = Promise[String]()
        val monitor = Polling.completionMonitor(actorSystem, Polling.props(promiseZipDone))
        log.info(s"completionMonitor is $monitor")
        val subscriberFlightActor = Polling.subscriberFlightActor(flightPassengerReporter,
          Polling.completionMessage(zipFileName, monitor))

        val eventualZipCompletion = promiseZipDone.future

        messagesToSendFuture.onFailure {
          case failure: Throwable =>
            log.warn(s"tickId: $tickId error in batch, on zip: '$zipFileName'.", failure)
            promiseZipDone.failure(failure)
            batchFileState.onZipFileProcessed(zipFileName)
        }
        messagesToSendFuture map { messages =>

          log.info(s"got messages to send ${messages}")
          val messageSource: Source[VoyagePassengerInfo, NotUsed] = Source(messages)
          val zipSendingGraph: RunnableGraph[NotUsed] = messageSource.map {
            x =>
              log.info(s"argx $x")
              x
          }.to(subscriberFlightActor)
          zipSendingGraph.run()

          eventualZipCompletion.onFailure{ case oc => log.error(s"tickId: $tickId processingZip had error ${oc}")}
          eventualZipCompletion.onSuccess {
            case zipFilename =>
              log.info(s"AdvPaxInfo: tickId: $tickId updating latestFile: $latestFile to $zipFileName complete $zipFilename")
              batchFileState.onZipFileProcessed(zipFileName)
              log.info(s"AdvPaxInfo: tickId: $tickId finished processing zip $zipFileName $latestFile")
          }
        }
        eventualZipCompletion
      })
  }

  private def manifestsFromZip(unzippedFileProvider: SimpleAtmosReader, materializer: Materializer, zipFileName: String)

  = {
    unzippedFileProvider.zipFilenameToEventualFileContent(zipFileName)(materializer, scala.concurrent.ExecutionContext.global)
  }

  private def manifestsToAdvPaxReporter(log: Logger, advPaxReporter: ActorRef, manifests: List[ZipUtils.UnzippedFileContent]): Seq[(Option[String], String, Try[VoyagePassengerInfo])]

  = {
    log.info(s"AdvPaxInfo: parsing ${manifests.length} manifests")
    val manifestsToSend = manifests.map((flightManifest) => {
      log.info(s"AdvPaxInfo: parsing manifest ${flightManifest.filename} from ${flightManifest.zipFilename}")
      (flightManifest.zipFilename, flightManifest.filename, VoyagePassengerInfoParser.parseVoyagePassengerInfo(flightManifest.content))
    })
    manifestsToSend
  }
}
