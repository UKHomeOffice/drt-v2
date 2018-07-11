package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, ArrivalsFeedResponse}

object DiffingStage {
  def DiffLists = new ArrivalsDiffingStage(diffLists)

  def diffLists[T](a: Seq[T], b: Seq[T]): Seq[T] = {
    val aSet = a.toSet
    val bSet = b.toSet

    (bSet -- aSet).toList
  }
}

final class ArrivalsDiffingStage(diff: (Seq[Arrival], Seq[Arrival]) => Seq[Arrival]) extends GraphStage[FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse]] {
  val in: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("DiffingStage.in")
  val out: Outlet[ArrivalsFeedResponse] = Outlet[ArrivalsFeedResponse]("DiffingStage.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var knownArrivals: Seq[Arrival] = Seq()
    private var maybeResponseToPush: Option[ArrivalsFeedResponse] = None

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        if (maybeResponseToPush.isEmpty) {
          log.info(s"Incoming ArrivalsFeedResponse")
          maybeResponseToPush = processFeedResponse(grab(in))
        } else log.info(s"Not ready to grab until we've pushed")

        if (isAvailable(out))
          pushAndClear()
      }

      override def onPull(): Unit = {
        pushAndClear()

        if (!hasBeenPulled(in)) pull(in)
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
          log.warn(s"Unexpected ArrivalsFeedResponse: ${unexpected.getClass}")
          Option.empty[ArrivalsFeedResponse]
      }
    }
  }
}
