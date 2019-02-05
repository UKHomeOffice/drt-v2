package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

final class ArrivalsDiffingStage(initialKnownArrivals: Seq[Arrival]) extends GraphStage[FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse]] {
  val in: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("DiffingStage.in")
  val out: Outlet[ArrivalsFeedResponse] = Outlet[ArrivalsFeedResponse]("DiffingStage.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var knownArrivals: Seq[Arrival] = initialKnownArrivals
    private var maybeResponseToPush: Option[ArrivalsFeedResponse] = None

    override def preStart(): Unit = {
      log.info(s"Started with ${knownArrivals.length} known arrivals")
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

    def processFeedResponse(ArrivalsFeedResponse: ArrivalsFeedResponse): Option[ArrivalsFeedResponse] = {
      ArrivalsFeedResponse match {
        case afs@ArrivalsFeedSuccess(latestArrivals, _) =>
          val newUpdates = diff(knownArrivals, latestArrivals.flights)
          log.info(s"Got ${newUpdates.length} new arrival updates")
          knownArrivals = latestArrivals.flights
          Option(afs.copy(arrivals = latestArrivals.copy(flights = newUpdates)))
        case aff@ArrivalsFeedFailure(_, _) =>
          log.info("Passing ArrivalsFeedFailure through. Nothing to diff. No updates to knownArrivals")
          Option(aff)
        case unexpected =>
          log.error(s"Unexpected ArrivalsFeedResponse: ${unexpected.getClass}")
          Option.empty[ArrivalsFeedResponse]
      }
    }

    def diff(existingArrivalsSeq: Seq[Arrival], newArrivalsSeq: Seq[Arrival]): Seq[Arrival] = {
      val existingArrival = existingArrivalsSeq.toSet

      def findTheExistingArrival(newArrival: Arrival): Option[Arrival] = existingArrival.find(newArrival.uniqueId == _.uniqueId)

      def isChoxTimeTheSameInBothSets(inInitialSet: Arrival, inNewSet: Arrival): Boolean =
        inInitialSet.ActualChox.exists(inNewSet.ActualChox.contains)

      def removeArrivalsWithAnUnchangedChoxTime(newArrivalsSet: Set[Arrival]): Set[Arrival] =
        newArrivalsSet.filterNot(newArrival => findTheExistingArrival(newArrival).exists(existingArrival => isChoxTimeTheSameInBothSets(existingArrival, newArrival)))

      val newArrivals = removeArrivalsWithAnUnchangedChoxTime(newArrivalsSeq.toSet)

      (newArrivals -- existingArrival).toList
    }
  }
}
