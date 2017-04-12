package passengersplits.polling

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.mfglabs.stream.SinkExt
import passengersplits.core.ZipUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import drt.shared.MilliDate
import passengersplits.core.PassengerInfoRouterActor.{FlightPaxSplitBatchComplete, FlightPaxSplitBatchInit, PassengerSplitsAck}
import passengersplits.s3._
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object FilePolling {
  def beginPolling(log: LoggingAdapter, flightPassengerReporter: ActorRef, zipFilePath: String,
                   initialFileFilter: Option[String], portCode: String)(implicit actorSystem: ActorSystem, mat: Materializer): Future[Done] = {
    val statefulPoller: StatefulLocalFileSystemPoller = StatefulLocalFileSystemPoller(initialFileFilter, zipFilePath)
    val unzippedFileProvider: SimpleLocalFileSystemReader = statefulPoller.unzippedFileProvider
    val onNewFileSeen = statefulPoller.onNewFileSeen

    val promiseBatchDone = PromiseSignals.promisedDone
    val batchPollProcessingDone = promiseBatchDone.future

    class BatchCompletionMonitor(promise: Promise[Done]) extends Actor with ActorLogging {
      def receive: Receive = {
        case FlightPaxSplitBatchComplete(_) =>
          log.info(s"$self FlightPaxSplitBatchComplete")
          promise.complete(Try(Done))
      }
    }
    val props = Props(classOf[BatchCompletionMonitor], promiseBatchDone)
    val completionMonitor = actorSystem.actorOf(props)


    val completionMessage = FlightPaxSplitBatchComplete(completionMonitor)

    val subscriberFlightActor = Sink.actorRefWithAck(flightPassengerReporter, FlightPaxSplitBatchInit, PassengerSplitsAck, completionMessage)

    val unzipFlow = Flow[String]
      .mapAsync(1)(unzippedFileProvider.zipFilenameToEventualFileContent(_))
      .mapConcat(unzippedFileContents => unzippedFileContents.map(uzfc => VoyagePassengerInfoParser.parseVoyagePassengerInfo(uzfc.content)))
      .collect {
        case Success(vpi) if vpi.ArrivalPortCode == portCode => vpi
      }.map(uzfc => {
      log.info(s"VoyagePaxSplits ${uzfc.summary}")
      uzfc
    })

    val unzippedSink = unzipFlow.to(subscriberFlightActor)
    val i = 1

    val runOnce = UnzipGraphStage.runOnce(log)(unzippedFileProvider.latestFilePaths) _

    val onBatchReadingFinished = (tryDone: Try[Done]) => log.info(s"Reading files finished")

    runOnce(i, onBatchReadingFinished, onNewFileSeen, unzippedSink)

    batchPollProcessingDone.onComplete {
      case Success(complete) =>
        log.info(s"FilePolling complete ${complete}")
      case Failure(f) =>
        log.error(f, s"FilePolling failed ${f}")

    }
    batchPollProcessingDone
  }
}


object AtmosFilePolling {
  def filesFromFile(listOfFiles: Seq[String], s: String) = {
    val regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r
    val filterFrom = s match {
      case regex(dateTime, _) => dateTime
      case _ => s
    }
    println(s"filterFrom: $filterFrom, s: $s")
    listOfFiles.filter(_ >= filterFrom)
  }

  def fileNameStartForDate(date: MilliDate) = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    val previousDay = SDate(date.millisSinceEpoch - oneDayInMillis)

    val year = previousDay.getFullYear().toInt - 2000
    f"drt_dq_$year${previousDay.getMonth()}%02d${previousDay.getDate()}%02d"
  }

  def beginPolling(log: LoggingAdapter,
                   flightPassengerReporter: ActorRef,
                   initialFileFilter: String,
                   atmosHost: String,
                   bucket: String,
                   portCode: String)(
                    implicit actorSystem: ActorSystem
                    , mat: Materializer
                  ) = {
    val unzippedFileProvider = StatefulAtmosPoller(Some(initialFileFilter), atmosHost, bucket).unzippedFileProvider

    var latestFile = initialFileFilter

    val source = Source.tick(0 seconds, 5 minutes, NotUsed)

    implicit val materializer = ActorMaterializer()

    source.runForeach { _ =>
      val futureZipFiles = unzippedFileProvider.createBuilder.listFilesAsStream(bucket).runWith(SinkExt.collect)
      futureZipFiles.foreach(
        fileNamesAndDates =>
          filesFromFile(fileNamesAndDates.map(_._1), latestFile)
            .foreach(zipFileName => {
              log.info(s"AdvPaxInfo: extracting manifests from zip $zipFileName")

              manifestsFromZip(unzippedFileProvider, materializer, zipFileName)
                .map(manifests => manifestsToAdvPaxReporter(log, flightPassengerReporter, manifests))

              log.info(s"AdvPaxInfo: finished processing zip $zipFileName")
              latestFile = zipFileName
            })
      )
    }
  }

  private def manifestsFromZip(unzippedFileProvider: SimpleAtmosReader, materializer: ActorMaterializer, zipFileName: String) = {
    unzippedFileProvider.zipFilenameToEventualFileContent(zipFileName)(materializer, scala.concurrent.ExecutionContext.global)
  }

  private def manifestsToAdvPaxReporter(log: LoggingAdapter, advPaxReporter: ActorRef, manifests: List[ZipUtils.UnzippedFileContent]) = {
    log.info(s"AdvPaxInfo: parsing ${manifests.length} manifests")
    manifests.foreach((flightManifest) => {
      log.info(s"AdvPaxInfo: manifest ${flightManifest.filename} from ${flightManifest.zipFilename}")
      VoyagePassengerInfoParser.parseVoyagePassengerInfo(flightManifest.content) match {
        case Success(vpi) =>
          advPaxReporter ! vpi
        case Failure(f) =>
          log.warning(s"Failed to parse voyage passenger info: From ${flightManifest.filename} in ${flightManifest.zipFilename}, error: $f")
      }
    })
  }
}
