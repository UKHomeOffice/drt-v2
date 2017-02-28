package passengersplits.polling
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


trait PollingAtmosReader[-In, +Out] {
  def log: LoggingAdapter

  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val executionContext: ExecutionContext

  val statefulPoller = AtmosStatefulPoller()
  val runOnce = FileSystemAkkaStreamReading.runOnce(log)(statefulPoller.unzippedFileProvider) _

  val millisBetweenAttempts = 40000
  val atMostForResponsesFromAtmos = 100000 seconds

  import PromiseSignals._

  def beginPolling[Mat2](sink: Graph[SinkShape[Any], Mat2]): Unit = {
    for (i <- Range(0, Int.MaxValue)) {
      log.info(s"Beginning run ${i}")
      val unzipFlow = Flow[String].mapAsync(10)(statefulPoller.unzippedFileProvider.zipFilenameToEventualFileContent(_)).mapConcat(t => t)

      val unzippedSink = unzipFlow.to(sink)
      val myDonePromise = promisedDone
      runOnce(i, (td) => myDonePromise.complete(td), statefulPoller.onNewFileSeen, unzippedSink)
      val resultOne = Await.result(myDonePromise.future, atMostForResponsesFromAtmos)
      log.info(s"Got result ${i}")

      //todo get rid of the sleep by inverting the flow such that this is triggered by a pulse, embrace the streams!
      Thread.sleep(millisBetweenAttempts)
    }
  }
}


object FilePolling {
  def beginPolling(log: LoggingAdapter, flightPassengerReporter: ActorRef, zipFilePath: String, initialFileFilter: Option[String])(implicit actorSystem: ActorSystem, mat: Materializer): Future[Done] = {
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

    val unzipFlow = Flow[String].mapAsync(128)(unzippedFileProvider.zipFilenameToEventualFileContent(_))
      .mapConcat(unzippedFileContents => unzippedFileContents.map(uzfc => VoyagePassengerInfoParser.parseVoyagePassengerInfo(uzfc.content)))
      .collect {
        case Success(vpi) if vpi.ArrivalPortCode == "STN" => vpi
      }.map(uzfc => {
      log.info(s"VoyagePaxSplits ${uzfc.summary}")
      uzfc
    })

    val unzippedSink = unzipFlow.to(subscriberFlightActor)
    val i = 1

    val runOnce = FileSystemAkkaStreamReading.runOnce(log)(unzippedFileProvider) _

    val onBatchReadingFinished = (tryDone: Try[Done]) => log.info(s"Reading files finished")

    runOnce(i, onBatchReadingFinished, onNewFileSeen, unzippedSink)

    batchPollProcessingDone.onComplete {
      case Success(complete) =>
        log.info(s"FilePolling complete ${complete}")
      case Failure(f) =>
        log.error(f, s"FilePolling failed ${f}")

    }
    batchPollProcessingDone
    //    val resultOne = Await.result(promiseDone.future, 10 seconds)
  }
}
