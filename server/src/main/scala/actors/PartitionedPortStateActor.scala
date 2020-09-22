package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor.DateRangeLike
import actors.acking.Acking
import actors.acking.Acking.AckingAsker
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily._
import actors.pointInTime.CrunchStateReadActor
import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object PartitionedPortStateActor {
  def queueUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayQueuesUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  def staffUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayStaffUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  def flightUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayFlightUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  def isNonLegacyRequest(pointInTime: SDateLike, legacyDataCutoff: SDateLike): Boolean =
    pointInTime.millisSinceEpoch >= legacyDataCutoff.millisSinceEpoch

  def tempLegacyActorProps(replayMaxMessages: Int)
                          (pointInTime: SDateLike,
                           message: DateRangeLike,
                           queues: Map[Terminal, Seq[Queue]],
                           expireAfterMillis: Int): Props = {
    Props(new CrunchStateReadActor(pointInTime, expireAfterMillis, queues, message.from, message.to, replayMaxMessages))
  }

  type QueueMinutesRequester = PortStateRequest => Future[MinutesContainer[CrunchMinute, TQM]]

  type StaffMinutesRequester = PortStateRequest => Future[MinutesContainer[StaffMinute, TM]]

  type FlightsRequester = PortStateRequest => Future[FlightsWithSplits]

  type PortStateUpdatesRequester = (MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, ActorRef) => Future[Option[PortStateUpdates]]

  type PortStateRequester = (ActorRef, PortStateRequest) => Future[PortState]

  type LegacyPortStateRequester = (ActorRef, ActorRef, DateRangeLike) => Future[Any]

  def requestQueueMinutesFn(actor: ActorRef)
                           (implicit timeout: Timeout, ec: ExecutionContext): QueueMinutesRequester =
    request => actor.ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the queues actors, for request $request", t)
    }

  def requestStaffMinutesFn(actor: ActorRef)
                           (implicit timeout: Timeout, ec: ExecutionContext): StaffMinutesRequester =
    request => actor.ask(request).mapTo[MinutesContainer[StaffMinute, TM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the staff actors, for request $request", t)
    }

  def requestFlightsFn(actor: ActorRef)
                      (implicit timeout: Timeout, ec: ExecutionContext): FlightsRequester =
    request => actor.ask(request).mapTo[FlightsWithSplits].recoverWith {
      case t => throw new Exception(s"Error receiving FlightsWithSplits from the flights actor, for request $request", t)
    }

  def replyWithUpdatesFn(flights: FlightsRequester, queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                        (implicit ec: ExecutionContext): PortStateUpdatesRequester =
    (since: MillisSinceEpoch, start: MillisSinceEpoch, end: MillisSinceEpoch, replyTo: ActorRef) => {
      val request = GetUpdatesSince(since, start, end)
      combineToPortStateUpdates(
        flights(request),
        queueMins(request),
        staffMins(request)
      ).pipeTo(replyTo)
    }

  def replyWithPortStateFn(flights: FlightsRequester, queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                          (implicit ec: ExecutionContext): PortStateRequester = (replyTo: ActorRef, request: PortStateRequest) =>
    combineToPortState(
      flights(request),
      queueMins(request),
      staffMins(request)
    ).pipeTo(replyTo)

  def replyWithMinutesAsPortStateFn(queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                                   (implicit ec: ExecutionContext): PortStateRequester =
    replyWithPortStateFn(_ => Future(FlightsWithSplits.empty), queueMins, staffMins)

  def replyWithLegacyPortStateFn(killActor: ActorRef)
                                (implicit timeout: Timeout, ec: ExecutionContext, system: ActorContext): (ActorRef, ActorRef, DateRangeLike) => Future[Any] =
    (tempActor: ActorRef, replyTo: ActorRef, message: DateRangeLike) => {
      killActor
        .ask(RequestAndTerminate(tempActor, message))
        .pipeTo(replyTo)
    }

  def stateAsTuple(eventualFlights: Future[FlightsWithSplits],
                   eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                   eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]])
                  (implicit ec: ExecutionContext): Future[(Iterable[ApiFlightWithSplits], Iterable[CrunchMinute], Iterable[StaffMinute])] =
    for {
      flights <- eventualFlights
      queueMinutes <- eventualQueueMinutes
      staffMinutes <- eventualStaffMinutes
    } yield {
      val fs = flights.flights.toMap.values
      val cms = queueMinutes.minutes.map(_.toMinute)
      val sms = staffMinutes.minutes.map(_.toMinute)
      (fs, cms, sms)
    }

  def combineToPortStateUpdates(eventualFlights: Future[FlightsWithSplits],
                                eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                                eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]])
                               (implicit ec: ExecutionContext): Future[Option[PortStateUpdates]] =
    stateAsTuple(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).map {
      case (fs, cms, sms) =>
        val flightUpdates = fs.map(_.lastUpdated.getOrElse(0L))
        val queueUpdates = cms.map(_.lastUpdated.getOrElse(0L))
        val staffUpdates = sms.map(_.lastUpdated.getOrElse(0L))
        flightUpdates ++ queueUpdates ++ staffUpdates match {
          case noUpdates if noUpdates.isEmpty =>
            None
          case millis =>
            Option(PortStateUpdates(millis.max, fs.toSet, cms.toSet, sms.toSet))
        }
    }

  def combineToPortState(eventualFlights: Future[FlightsWithSplits],
                         eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                         eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]])
                        (implicit ec: ExecutionContext): Future[PortState] =
    stateAsTuple(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).map {
      case (fs, cms, sms) => PortState(fs, cms, sms)
    }

  trait DateRangeLike {
    val from: MillisSinceEpoch
    val to: MillisSinceEpoch
  }

  trait PortStateRequest extends DateRangeLike

  trait FlightsRequest extends DateRangeLike

  trait TerminalRequest extends DateRangeLike {
    val terminal: Terminal
  }

  case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest

  case class PointInTimeQuery(pointInTime: MillisSinceEpoch, query: DateRangeLike) extends PortStateRequest {
    override val from: MillisSinceEpoch = query.from
    override val to: MillisSinceEpoch = query.to
  }

  case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch) extends FlightsRequest

  case class GetFlightsForTerminal(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends FlightsRequest with TerminalRequest

  case class GetScheduledFlightsForTerminal(utcDate: UtcDate, terminal: Terminal) extends FlightsRequest {
    val start: SDateLike = SDate(utcDate)
    val end: SDateLike = start.addDays(1).addMinutes(-1)
    override val from: MillisSinceEpoch = start.millisSinceEpoch
    override val to: MillisSinceEpoch = end.millisSinceEpoch
  }

  case class GetStateForDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest

  case class GetStateForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends PortStateRequest with TerminalRequest

  case class GetMinutesForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends PortStateRequest with TerminalRequest

}

trait PartitionedPortStateActorLike {
  val now: () => SDateLike
  val context: ActorContext
  val journalType: StreamingJournalLike
  val queues: Map[Terminal, Seq[Queue]]

  def terminals: List[Terminal] = queues.keys.toList

  val queueUpdatesSupervisor: ActorRef
  val staffUpdatesSupervisor: ActorRef
}

class PartitionedPortStateActor(flightsActor: ActorRef,
                                queuesActor: ActorRef,
                                staffActor: ActorRef,
                                queueUpdatesActor: ActorRef,
                                staffUpdatesActor: ActorRef,
                                flightUpdatesActor: ActorRef,
                                val now: () => SDateLike,
                                val queues: Map[Terminal, Seq[Queue]],
                                val journalType: StreamingJournalLike,
                                legacyDataCutoff: SDateLike,
                                tempLegacyActorProps: (SDateLike, DateRangeLike, Map[Terminal, Seq[Queue]], Int) => Props) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PartitionedPortStateActor._

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 hours)

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

  val requestStaffMinuteUpdates: StaffMinutesRequester = requestStaffMinutesFn(staffUpdatesActor)
  val requestQueueMinuteUpdates: QueueMinutesRequester = requestQueueMinutesFn(queueUpdatesActor)
  val requestFlightUpdates: FlightsRequester = requestFlightsFn(flightUpdatesActor)
  val requestStaffMinutes: StaffMinutesRequester = requestStaffMinutesFn(staffActor)
  val requestQueueMinutes: QueueMinutesRequester = requestQueueMinutesFn(queuesActor)
  val requestFlights: FlightsRequester = requestFlightsFn(flightsActor)
  val replyWithUpdates: PortStateUpdatesRequester = replyWithUpdatesFn(requestFlightUpdates, requestQueueMinuteUpdates, requestStaffMinuteUpdates)
  val replyWithPortState: PortStateRequester = replyWithPortStateFn(requestFlights, requestQueueMinutes, requestStaffMinutes)
  val replyWithMinutesAsPortState: PortStateRequester = replyWithMinutesAsPortStateFn(requestQueueMinutes, requestStaffMinutes)
  val replyWithLegacyPortState: LegacyPortStateRequester = replyWithLegacyPortStateFn(killActor)
  val askThenAck: AckingAsker = Acking.askThenAck

  def processMessage: Receive = {
    case msg: SetDeploymentQueueActor =>
      log.info(s"Received deployment queue actor")
      queuesActor ! msg

    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case flightsWithSplits: FlightsWithSplitsDiff =>
      val replyTo = sender()
      askThenAck(flightsActor, flightsWithSplits, replyTo)

    case noUpdates: PortStateMinutes[_, _] if noUpdates.isEmpty =>
      sender() ! Ack

    case someQueueUpdates: PortStateQueueMinutes =>
      val replyTo = sender()
      askThenAck(queuesActor, someQueueUpdates.asContainer, replyTo)

    case someStaffUpdates: PortStateStaffMinutes =>
      val replyTo = sender()
      askThenAck(staffActor, someStaffUpdates.asContainer, replyTo)

    case GetUpdatesSince(since, from, to) => replyWithUpdates(since, from, to, sender())

    case pitRequest@PointInTimeQuery(millis, request: GetStateForDateRange) =>
      replyToPitDateRangeQuery(millis, replyWithPortState(sender(), pitRequest), request)

    case pitRequest@PointInTimeQuery(millis, request: GetStateForTerminalDateRange) =>
      replyToPitDateRangeQuery(millis, replyWithPortState(sender(), pitRequest), request)

    case pitRequest@PointInTimeQuery(millis, request: GetMinutesForTerminalDateRange) =>
      replyToPitDateRangeQuery(millis, replyWithMinutesAsPortState(sender(), pitRequest), request)

    case pitRequest@PointInTimeQuery(millis, request: FlightsRequest) =>
      replyToPitDateRangeQuery(millis, flightsActor.ask(pitRequest).pipeTo(sender()), request)

    case request: GetStateForDateRange =>
      replyToDateRangeQuery(request, replyWithPortState(sender(), request), sender())

    case request: GetStateForTerminalDateRange =>
      replyToDateRangeQuery(request, replyWithPortState(sender(), request), sender())

    case request: GetMinutesForTerminalDateRange =>
      replyToDateRangeQuery(request, replyWithMinutesAsPortState(sender(), request), sender())

    case request: FlightsRequest =>
      log.info(s"Received Flights request $request")
      replyToDateRangeQuery(request, flightsActor.ask(request).pipeTo(sender()), sender())
  }

  private def replyToPitDateRangeQuery(millis: MillisSinceEpoch,
                                       nonLegacyQuery: => Future[Any],
                                       legacyRequest: DateRangeLike): Future[Any] =
    if (PartitionedPortStateActor.isNonLegacyRequest(SDate(millis), legacyDataCutoff))
      nonLegacyQuery
    else
      replyWithLegacyPortStateLookup(millis, legacyRequest, sender())

  private def replyToDateRangeQuery(request: DateRangeLike, currentLookupFn: => Future[Any], replyTo: ActorRef): Future[Any] =
    if (PartitionedPortStateActor.isNonLegacyRequest(SDate(request.to), legacyDataCutoff))
      currentLookupFn
    else {
      val pitMillis = SDate(request.to).addHours(4).millisSinceEpoch
      replyWithLegacyPortStateLookup(pitMillis, request, replyTo)
    }

  private def replyWithLegacyPortStateLookup(millis: MillisSinceEpoch, request: DateRangeLike, replyTo: ActorRef): Future[Any] = {
    val tempActor = context.actorOf(tempLegacyActorProps(SDate(millis), request, queues, expireAfterMillis))
    replyWithLegacyPortState(tempActor, replyTo, request)
  }

  override def receive: Receive = processMessage orElse {
    case unexpected => log.warn(s"Got unexpected: ${unexpected.getClass}")
  }
}
