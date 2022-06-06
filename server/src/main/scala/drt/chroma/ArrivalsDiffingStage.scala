package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.metrics.StageTimer
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.collection.immutable.SortedMap


final class ArrivalsDiffingStage(initialKnownArrivals: SortedMap[UniqueArrival, Arrival], forecastMaxMillis: () => MillisSinceEpoch)
  extends GraphStage[FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse]] {
  val in: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("DiffingStage.in")
  val out: Outlet[ArrivalsFeedResponse] = Outlet[ArrivalsFeedResponse]("DiffingStage.out")
  val stageName = "arrivals-diffing"

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[ArrivalsFeedResponse, ArrivalsFeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var knownArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival]()
    var maybeResponseToPush: Option[ArrivalsFeedResponse] = None

    knownArrivals = knownArrivals ++ initialKnownArrivals

    override def preStart(): Unit = {
      log.info(s"Started with ${knownArrivals.size} known arrivals")
      super.preStart()
    }

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, in)
        maybeResponseToPush = processFeedResponse(grab(in))

        if (isAvailable(out)) pushAndClear()
        timer.stopAndReport()
      }

      override def onPull(): Unit = {
        val timer = StageTimer(stageName, in)
        pushAndClear()

        if (!hasBeenPulled(in)) pull(in)
        timer.stopAndReport()
      }
    })

    def pushAndClear(): Unit = {
      maybeResponseToPush.collect {
        case afs: ArrivalsFeedResponse =>
          push(out, afs)
      }
      maybeResponseToPush = None
    }

    def processFeedResponse(arrivalsFeedResponse: ArrivalsFeedResponse): Option[ArrivalsFeedResponse] = arrivalsFeedResponse match {
      case afs@ArrivalsFeedSuccess(latestArrivals, _) =>
        val maxScheduledMillis = forecastMaxMillis()
        val incomingArrivals: Iterable[(UniqueArrival, Arrival)] = latestArrivals.flights.filter(_.Scheduled <= maxScheduledMillis).map(a => (UniqueArrival(a), a))
        val newUpdates: Iterable[(UniqueArrival, Arrival)] = filterArrivalsWithUpdates(knownArrivals, incomingArrivals)
        if (newUpdates.nonEmpty) log.info(s"Got ${newUpdates.size} new arrival updates")
        knownArrivals = SortedMap[UniqueArrival, Arrival]() ++ incomingArrivals
        Option(afs.copy(arrivals = latestArrivals.copy(flights = newUpdates.map(_._2))))
      case aff@ArrivalsFeedFailure(_, _) =>
        log.info("Passing ArrivalsFeedFailure through. Nothing to diff. No updates to knownArrivals")
        Option(aff)
      case unexpected =>
        log.error(s"Unexpected ArrivalsFeedResponse: ${unexpected.getClass}")
        None
    }

    def filterArrivalsWithUpdates(existingArrivals: SortedMap[UniqueArrival, Arrival],
                                  newArrivals: Iterable[(UniqueArrival, Arrival)]): Iterable[(UniqueArrival, Arrival)] = newArrivals
      .foldLeft(List[(UniqueArrival, Arrival)]()) {
        case (soFar, (key, incomingArrival)) => existingArrivals.get(key) match {
          case None => (key, incomingArrival) :: soFar
          case Some(existingArrival) if existingArrival == incomingArrival => soFar
          case Some(existingArrival) if unchangedExistingActChox(incomingArrival, existingArrival) => soFar
          case Some(existingArrival) =>
            val updated = existingArrival.update(incomingArrival)
            if (!updated.isEqualTo(existingArrival)) (key, updated) :: soFar else soFar
        }
      }

    def unchangedExistingActChox(arrival: Arrival, existingArrival: Arrival): Boolean =
      existingArrival.ActualChox.isDefined && arrival.ActualChox == existingArrival.ActualChox
  }
}
