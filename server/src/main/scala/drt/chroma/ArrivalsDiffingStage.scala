package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.{Arrival, ArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.immutable.SortedMap

final class ArrivalsDiffingStage(initialKnownArrivals: Seq[Arrival]) extends GraphStage[FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse]] {
  val in: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("DiffingStage.in")
  val out: Outlet[ArrivalsFeedResponse] = Outlet[ArrivalsFeedResponse]("DiffingStage.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var knownArrivals: SortedMap[ArrivalKey, Arrival] = SortedMap[ArrivalKey, Arrival]() ++ initialKnownArrivals.map(a => (ArrivalKey(a), a))
    private var maybeResponseToPush: Option[ArrivalsFeedResponse] = None

    override def preStart(): Unit = {
      log.info(s"Started with ${knownArrivals.size} known arrivals")
      super.preStart()
    }

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        if (maybeResponseToPush.isEmpty) {
          log.info(s"Incoming ArrivalsFeedResponse")
          maybeResponseToPush = processFeedResponse(grab(in))
        } else log.info(s"Not ready to grab until we've pushed")

        if (isAvailable(out))
          pushAndClear()

        log.info(s"onPush Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }

      override def onPull(): Unit = {
        val start = SDate.now()
        pushAndClear()

        if (!hasBeenPulled(in)) pull(in)
        log.info(s"onPull Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pushAndClear(): Unit = {
      maybeResponseToPush.foreach(responseToPush => push(out, responseToPush))
      maybeResponseToPush = None
    }

    def processFeedResponse(ArrivalsFeedResponse: ArrivalsFeedResponse): Option[ArrivalsFeedResponse] = ArrivalsFeedResponse match {
      case afs@ArrivalsFeedSuccess(latestArrivals, _) =>
        val incomingArrivals = SortedMap[ArrivalKey, Arrival]() ++ latestArrivals.flights.map(a => (ArrivalKey(a), a))
        val newUpdates = diff(knownArrivals, incomingArrivals)
        log.info(s"Got ${newUpdates.size} new arrival updates")
        knownArrivals = incomingArrivals
        Option(afs.copy(arrivals = latestArrivals.copy(flights = newUpdates.values.toSeq)))
      case aff@ArrivalsFeedFailure(_, _) =>
        log.info("Passing ArrivalsFeedFailure through. Nothing to diff. No updates to knownArrivals")
        Option(aff)
      case unexpected =>
        log.error(s"Unexpected ArrivalsFeedResponse: ${unexpected.getClass}")
        None
    }

    def diff(existingArrivals: SortedMap[ArrivalKey, Arrival], newArrivals: SortedMap[ArrivalKey, Arrival]): SortedMap[ArrivalKey, Arrival] = newArrivals
      .foldLeft(SortedMap[ArrivalKey, Arrival]()) {
        case (soFar, (key, arrival)) => existingArrivals.get(key) match {
          case None => soFar.updated(key, arrival)
          case Some(existingArrival) if existingArrival == arrival => soFar
          case Some(existingArrival) if existingArrival.ActualChox.isDefined && arrival.ActualChox == existingArrival.ActualChox => soFar
          case _ => soFar.updated(key, arrival)
        }
      }
  }
}
