package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, FeedResponse}

object DiffingStage {
  def DiffLists = new ArrivalsDiffingStage(diffLists)

  def diffLists[T](a: Seq[T], b: Seq[T]): Seq[T] = {
    val aSet = a.toSet
    val bSet = b.toSet

    (bSet -- aSet).toList
  }
}

final class ArrivalsDiffingStage(diff: (Seq[Arrival], Seq[Arrival]) => Seq[Arrival]) extends GraphStage[FlowShape[FeedResponse, FeedResponse]] {
  val in: Inlet[FeedResponse] = Inlet[FeedResponse]("DiffingStage.in")
  val out: Outlet[FeedResponse] = Outlet[FeedResponse]("DiffingStage.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[FeedResponse, FeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var knownArrivals: Seq[Arrival] = Seq()
    private var maybeResponseToPush: Option[FeedResponse] = None

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        if (maybeResponseToPush.isEmpty) {
          log.info(s"Incoming FeedResponse")
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

    def processFeedResponse(feedResponse: FeedResponse): Option[FeedResponse] = {
      feedResponse match {
        case afs@ArrivalsFeedSuccess(latestArrivals, _) =>
          val newUpdates = diff(knownArrivals, latestArrivals.flights)
          log.info(s"Got ${newUpdates.length} new arrival updates")
          knownArrivals = latestArrivals.flights
          Option(afs.copy(arrivals = latestArrivals.copy(flights = newUpdates)))
        case aff@ArrivalsFeedFailure(_, _) =>
          log.info("Passing ArrivalsFeedFailure through. Nothing to diff. No updates to knownArrivals")
          Option(aff)
        case unexpected =>
          log.warn(s"Unexpected FeedResponse: ${unexpected.getClass}")
          Option.empty[FeedResponse]
      }
    }
  }
}
