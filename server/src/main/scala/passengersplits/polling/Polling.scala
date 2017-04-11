package passengersplits.polling

import java.util.Date

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.mfglabs.stream.SinkExt
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
//import scala.collection.immutable.Seq
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{Graph, Materializer, SinkShape}
import passengersplits.core.PassengerInfoRouterActor.{FlightPaxSplitBatchComplete, FlightPaxSplitBatchInit, PassengerSplitsAck}
import passengersplits.s3._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
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

  def beginPolling(log: LoggingAdapter,
                   flightPassengerReporter: ActorRef,
                   initialFileFilter: String,
                   atmosHost: String,
                   bucket: String,
                   portCode: String)(
                    implicit actorSystem: ActorSystem
                    , mat: Materializer
                  ) = {
    val statefulPoller: StatefulAtmosPoller = StatefulAtmosPoller(Some(initialFileFilter), atmosHost, bucket)
    val unzippedFileProvider: SimpleAtmosReader = statefulPoller.unzippedFileProvider
    //    val onNewFileSeen: (String) => Unit = statefulPoller.onNewFileSeen

    //    val promiseBatchDone: Promise[Done] = PromiseSignals.promisedDone


    //    def singleBatch(batchId: Int) = {
    //      log.info(s"!!!!Running batch! ${batchId}")
    //      runSingleBatch(batchId,
    //        promiseBatchDone, flightPassengerReporter, unzippedFileProvider, onNewFileSeen, log,
    //        portCode)
    //    }

    var latestFile = initialFileFilter
    var batchId = 0

    val source = Source.tick(0 seconds, 2 minutes, NotUsed)
    implicit val materializer = ActorMaterializer()
    source.runForeach { (td) =>
      batchId = batchId + 1
      val futurefiles: Future[IndexedSeq[(String, Date)]] = unzippedFileProvider.createBuilder.listFilesAsStream(bucket).runWith(SinkExt.collect)
      futurefiles.map {
        (fileNamesAndDate: IndexedSeq[(String, Date)]) =>
          val filesToProcess: Seq[String] = filesFromFile(fileNamesAndDate.map(_._1), latestFile)
          filesToProcess.map(fileName => {
            val filesFuture = unzippedFileProvider.zipFilenameToEventualFileContent(fileName)(materializer, scala.concurrent.ExecutionContext.global)
            filesFuture.map(files => {
              val voyagePassengerInfos = files.map((flightManifest) => {
                val tvpi: Try[VoyagePassengerInfo] = VoyagePassengerInfoParser.parseVoyagePassengerInfo(flightManifest.content)
                tvpi match {
                  case Success(vpi) =>
                    flightPassengerReporter ! vpi
                  case Failure(f) =>
                    log.warning(s"Failed to parse voyage passenger info: From ${flightManifest.filename} in ${flightManifest.zipFilename}, error: $f")
                }
              })
            })
            latestFile = fileName
          })
      }


      //      singleBatch(batchId)
    }
    //    val resultOne = Await.result(promiseDone.future, 10 seconds)
  }

  def runSingleBatch(batchid: Int,
                     promiseBatchDone: Promise[Done],
                     flightPassengerReporter: ActorRef,
                     unzippedFileProvider: SimpleAtmosReader,
                     onNewFileSeen: (String) => Unit,
                     log: LoggingAdapter,
                     portCode: String
                    )(implicit actorSystem: ActorSystem, mat: Materializer) = {
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

    val runOnce = UnzipGraphStage.runOnce(log)(unzippedFileProvider.latestFilePaths) _

    val onBatchReadingFinished = (tryDone: Try[Done]) => log.info(s"Reading files finished")

    runOnce(batchid, onBatchReadingFinished, onNewFileSeen, unzippedSink)

    batchPollProcessingDone.onComplete {
      case Success(complete) =>
        log.info(s"FilePolling complete ${complete}")
      case Failure(f) =>
        log.error(f, s"FilePolling failed ${f}")

    }
    batchPollProcessingDone
  }
}
