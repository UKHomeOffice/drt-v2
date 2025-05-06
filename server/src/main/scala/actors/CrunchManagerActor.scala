package actors

import actors.CrunchManagerActor._
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object CrunchManagerActor {
  private val log = LoggerFactory.getLogger(getClass)

  case class AddQueueCrunchSubscriber(subscriber: ActorRef)

  case class AddQueueRecalculateArrivalsSubscriber(subscriber: ActorRef)

  case class AddQueueRecalculateLiveSplitsSubscriber(subscriber: ActorRef)

  case class AddQueueHistoricSplitsLookupSubscriber(subscriber: ActorRef)

  case class AddQueueHistoricPaxLookupSubscriber(subscriber: ActorRef)

  trait ReProcessDates {
    val updatedMillis: Set[Long]
  }

  case class RecalculateArrivals(updatedMillis: Set[Long]) extends ReProcessDates

  case class RecalculateLiveSplits(updatedMillis: Set[Long]) extends ReProcessDates

  case class RecalculateHistoricSplits(updatedMillis: Set[Long]) extends ReProcessDates

  case class Recrunch(updatedMillis: Set[Long]) extends ReProcessDates

  case class LookupHistoricSplits(updatedMillis: Set[Long]) extends ReProcessDates

  case class LookupHistoricPaxNos(updatedMillis: Set[Long]) extends ReProcessDates

  def missingHistoricSplitsArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                             )
                                             (implicit mat: Materializer): UtcDate => Future[Seq[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map { case (_, arrivals) =>
        arrivals
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(!_.splits.exists(_.source == Historical))
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))

  def historicSplitsArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                      )
                                      (implicit mat: Materializer, ec: ExecutionContext): UtcDate => Future[Seq[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map { case (_, arrivals) =>
        arrivals
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(_.splits.exists(_.source == Historical))
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))
      .map { keys =>
        log.info(s"Found ${keys.size} arrival keys for historic splits recalculation for ${date.toISOString}")
        keys
      }

  def missingPaxArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  )
                                  (implicit mat: Materializer): UtcDate => Future[Seq[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map { case (_, arrivals) =>
        arrivals
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(_.apiFlight.hasNoPaxSource)
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))

  def liveSplitsArrivalKeysForDate(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  )
                                  (implicit mat: Materializer): UtcDate => Future[Seq[UniqueArrival]] =
    date => allTerminalsFlights(date, date)
      .map { case (_, arrivals) =>
        arrivals
          .filter(!_.apiFlight.Origin.isDomesticOrCta)
          .filter(_.splits.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages))
          .map(_.unique)
      }
      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))

  def props(allTerminalsFlights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
           )
           (implicit ec: ExecutionContext, mat: Materializer, ac: AirportConfig): Props = {
    val missingHistoricSplitsArrivalKeysForDate = CrunchManagerActor.missingHistoricSplitsArrivalKeysForDate(allTerminalsFlights)
    val historicSplitsArrivalKeysForDate = CrunchManagerActor.historicSplitsArrivalKeysForDate(allTerminalsFlights)
    val missingPaxArrivalKeysForDate = CrunchManagerActor.missingPaxArrivalKeysForDate(allTerminalsFlights)

    Props(new CrunchManagerActor(missingHistoricSplitsArrivalKeysForDate, historicSplitsArrivalKeysForDate, missingPaxArrivalKeysForDate))
  }
}

class CrunchManagerActor(missingHistoricManifestArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],
                         historicManifestArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],
                         historicPaxArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],
                        )
                        (implicit ec: ExecutionContext, mat: Materializer, ac: AirportConfig) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  private var maybeQueueCrunchSubscriber: Option[ActorRef] = None
  private var maybeQueueRecalculateArrivalsSubscriber: Option[ActorRef] = None
  private var maybeQueueRecalculateLiveSplitsLookupSubscriber: Option[ActorRef] = None
  private var maybeQueueHistoricSplitsLookupSubscriber: Option[ActorRef] = None
  private var maybeQueueHistoricPaxLookupSubscriber: Option[ActorRef] = None

  override def receive: Receive = {
    case AddQueueCrunchSubscriber(subscriber) =>
      maybeQueueCrunchSubscriber = Option(subscriber)

    case AddQueueRecalculateArrivalsSubscriber(subscriber) =>
      maybeQueueRecalculateArrivalsSubscriber = Option(subscriber)

    case AddQueueRecalculateLiveSplitsSubscriber(subscriber) =>
      maybeQueueRecalculateLiveSplitsLookupSubscriber = Option(subscriber)

    case AddQueueHistoricSplitsLookupSubscriber(subscriber) =>
      maybeQueueHistoricSplitsLookupSubscriber = Option(subscriber)

    case AddQueueHistoricPaxLookupSubscriber(subscriber) =>
      maybeQueueHistoricPaxLookupSubscriber = Option(subscriber)

    case Recrunch(um) =>
      val updateRequests = millisToTerminalUpdateRequests(um)
      maybeQueueCrunchSubscriber.foreach(_ ! updateRequests)

    case RecalculateArrivals(um) =>
      val updateRequests = millisToTerminalUpdateRequests(um)
      maybeQueueRecalculateArrivalsSubscriber.foreach(_ ! updateRequests)

    case RecalculateLiveSplits(um) =>
      maybeQueueRecalculateLiveSplitsLookupSubscriber.foreach(sub => um.map(ms => sub ! SDate(ms).toUtcDate))

    case RecalculateHistoricSplits(um) =>
      queueLookups(um, maybeQueueHistoricSplitsLookupSubscriber, historicManifestArrivalKeys, "historic splits")

    case LookupHistoricSplits(um) =>
      queueLookups(um, maybeQueueHistoricSplitsLookupSubscriber, missingHistoricManifestArrivalKeys, "mising historic splits")

    case LookupHistoricPaxNos(um) =>
      queueLookups(um, maybeQueueHistoricPaxLookupSubscriber, historicPaxArrivalKeys, "mising historic pax nos")

    case other =>
      log.warn(s"CrunchManagerActor received unexpected message: $other")
  }

  private def millisToTerminalUpdateRequests(um: Set[Long]): Set[TerminalUpdateRequest] = {
    um.map(ms => SDate(ms).toLocalDate)
      .flatMap(ld => ac.terminals.map(TerminalUpdateRequest(_, ld)))
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
