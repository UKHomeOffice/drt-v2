package actors

import actors.CrunchManagerActor._
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object CrunchManagerActor {
  case class AddQueueCrunchSubscriber(subscriber: ActorRef)

  case class AddRecalculateArrivalsSubscriber(subscriber: ActorRef)

  case class AddQueueHistoricSplitsLookupSubscriber(subscriber: ActorRef)

  case class AddQueueHistoricPaxLookupSubscriber(subscriber: ActorRef)

  trait ReProcessDates {
    val updatedMillis: Set[Long]
  }

  case class RecalculateArrivals(updatedMillis: Set[Long]) extends ReProcessDates

  case class Recrunch(updatedMillis: Set[Long]) extends ReProcessDates

  case class LookupHistoricSplits(updatedMillis: Set[Long]) extends ReProcessDates

  case class LookupHistoricPaxNos(updatedMillis: Set[Long]) extends ReProcessDates

  def missingHistoricSplitsArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                             )
                                             (implicit mat: Materializer): UtcDate => Future[Iterable[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map {
        _._2
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(!_.splits.exists(_.source == Historical))
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))

  def missingPaxArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  )
                                  (implicit mat: Materializer): UtcDate => Future[Iterable[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map {
        _._2
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(_.apiFlight.hasNoPaxSource)
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))

  def props(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
           )
           (implicit ec: ExecutionContext, mat: Materializer): Props = {
    val missingHistoricSplitsArrivalKeysForDate = CrunchManagerActor.missingHistoricSplitsArrivalKeysForDate(allTerminalsFlights)
    val missingPaxArrivalKeysForDate = CrunchManagerActor.missingPaxArrivalKeysForDate(allTerminalsFlights)

    Props(new CrunchManagerActor(missingHistoricSplitsArrivalKeysForDate, missingPaxArrivalKeysForDate))
  }
}

class CrunchManagerActor(historicManifestArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],
                         historicPaxArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],
                        )
                        (implicit ec: ExecutionContext, mat: Materializer) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  private var maybeQueueCrunchSubscriber: Option[ActorRef] = None
  private var maybeRecalculateArrivalsSubscriber: Option[ActorRef] = None
  private var maybeQueueHistoricSplitsLookupSubscriber: Option[ActorRef] = None
  private var maybeQueueHistoricPaxLookupSubscriber: Option[ActorRef] = None

  override def receive: Receive = {
    case AddQueueCrunchSubscriber(subscriber) =>
      maybeQueueCrunchSubscriber = Option(subscriber)

    case AddRecalculateArrivalsSubscriber(subscriber) =>
      maybeRecalculateArrivalsSubscriber = Option(subscriber)

    case AddQueueHistoricSplitsLookupSubscriber(subscriber) =>
      maybeQueueHistoricSplitsLookupSubscriber = Option(subscriber)

    case AddQueueHistoricPaxLookupSubscriber(subscriber) =>
      maybeQueueHistoricPaxLookupSubscriber = Option(subscriber)

    case Recrunch(um) =>
      maybeQueueCrunchSubscriber.foreach(_ ! um)

    case RecalculateArrivals(um) =>
      maybeRecalculateArrivalsSubscriber.foreach(_ ! um)

    case LookupHistoricSplits(um) =>
      queueLookups(um, maybeQueueHistoricSplitsLookupSubscriber, historicManifestArrivalKeys, "historic splits")

    case LookupHistoricPaxNos(um) =>
      queueLookups(um, maybeQueueHistoricPaxLookupSubscriber, historicPaxArrivalKeys, "historic pax nos")
  }

  private def queueLookups(millis: Set[Long], subscriber: Option[ActorRef], lookup: UtcDate => Future[Iterable[UniqueArrival]], label: String)
  : Unit =
    Source(millis
      .map(SDate(_).toUtcDate).toList
      .sorted)
      .mapAsync(1) { date =>
        lookup(date)
          .map { keys =>
            if (keys.nonEmpty) {
              log.info(s"Looking up ${keys.size} $label for ${date.toISOString}")
              subscriber.foreach(_ ! keys)
            }
          }
      }
      .runWith(Sink.ignore)
}
