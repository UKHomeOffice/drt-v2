package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor.{DateRangeLike, tempLegacyActorProps}
import actors.acking.Acking
import actors.acking.Acking.AckingAsker
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily._
import actors.pointInTime.CrunchStateReadActor
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
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
  def apply(now: () => SDateLike,
            airportConfig: AirportConfig,
            journalType: StreamingJournalLike,
            legacyDataCutoff: SDateLike,
            replayMaxCrunchStateMessages: Int,
            minuteLookups: MinuteLookupsLike)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val flightsActor: ActorRef = system.actorOf(Props(new FlightsStateActor(now, expireAfterMillis, airportConfig.queuesByTerminal, legacyDataCutoff, replayMaxCrunchStateMessages)))
    val queuesActor: ActorRef = minuteLookups.queueMinutesActor
    val staffActor: ActorRef = minuteLookups.staffMinutesActor
    val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(now, journalType))), "updates-supervisor-queues")
    val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(now, journalType))), "updates-supervisor-staff")
    system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, queueUpdates, staffUpdates, now, airportConfig.queuesByTerminal, journalType, legacyDataCutoff, tempLegacyActorProps(replayMaxCrunchStateMessages))))
  }

  def queueUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayQueuesUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  def staffUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayStaffUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
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

  type UpdatesRequester = (MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, ActorRef) => Future[Option[PortStateUpdates]]

  type PortStateRequester = (ActorRef, PortStateRequest) => Future[PortState]

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
                        (implicit ec: ExecutionContext):
  (MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, ActorRef) => Future[Option[PortStateUpdates]] =
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
                                   (implicit ec: ExecutionContext): PortStateRequester = (replyTo: ActorRef, request: PortStateRequest) =>
    combineToPortState(
      Future(FlightsWithSplits.empty),
      queueMins(request),
      staffMins(request)
    ).pipeTo(replyTo)

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

  trait PortStateRequest

  case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch) extends DateRangeLike with PortStateRequest

  case class PointInTimeQuery(pointInTime: MillisSinceEpoch, query: DateRangeLike) extends PortStateRequest

  case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch) extends DateRangeLike

  case class GetFlightsForTerminal(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends DateRangeLike

  case class GetStateForDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch) extends DateRangeLike with PortStateRequest

  case class GetStateForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends DateRangeLike with PortStateRequest

  case class GetMinutesForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends DateRangeLike with PortStateRequest

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
                                val now: () => SDateLike,
                                val queues: Map[Terminal, Seq[Queue]],
                                val journalType: StreamingJournalLike,
                                legacyDataCutoff: SDateLike,
                                tempLegacyActorProps: (SDateLike, DateRangeLike, Map[Terminal, Seq[Queue]], Int) => Props) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PartitionedPortStateActor._

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

  val requestStaffMinuteUpdates: StaffMinutesRequester = requestStaffMinutesFn(staffUpdatesActor)
  val requestQueueMinuteUpdates: QueueMinutesRequester = requestQueueMinutesFn(queueUpdatesActor)
  val requestStaffMinutes: StaffMinutesRequester = requestStaffMinutesFn(staffActor)
  val requestQueueMinutes: QueueMinutesRequester = requestQueueMinutesFn(queuesActor)
  val requestFlights: FlightsRequester = requestFlightsFn(flightsActor)
  val replyWithUpdates: UpdatesRequester = replyWithUpdatesFn(requestFlights, requestQueueMinuteUpdates, requestStaffMinuteUpdates)
  val replyWithPortState: PortStateRequester = replyWithPortStateFn(requestFlights, requestQueueMinutes, requestStaffMinutes)
  val replyWithMinutesAsPortState: PortStateRequester = replyWithMinutesAsPortStateFn(requestQueueMinutes, requestStaffMinutes)
  val replyWithLegacyPortState = replyWithLegacyPortStateFn(killActor)
  val askThenAck: AckingAsker = Acking.askThenAck

  def processMessage: Receive = {
    case msg: SetCrunchQueueActor =>
      log.info(s"Received crunch queue actor")
      flightsActor ! msg

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

    case PointInTimeQuery(millis, request: GetStateForDateRange) =>
      if (PartitionedPortStateActor.isNonLegacyRequest(SDate(millis), legacyDataCutoff))
        replyWithPortState(sender(), PointInTimeQuery(millis, request))
      else {
        val tempActor = context.actorOf(tempLegacyActorProps(SDate(millis), request, queues, expireAfterMillis))
        replyWithLegacyPortState(tempActor, sender(), request)
      }

    case PointInTimeQuery(millis, request: GetStateForTerminalDateRange) =>
      replyWithPortState(sender(), PointInTimeQuery(millis, request))

    case PointInTimeQuery(millis, GetMinutesForTerminalDateRange(from, to, terminal)) =>
      replyWithMinutesAsPortState(sender(), PointInTimeQuery(millis, GetStateForTerminalDateRange(from, to, terminal)))

    case PointInTimeQuery(pitMillis, GetFlights(from, to)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForDateRange(from, to))).pipeTo(sender())

    case PointInTimeQuery(pitMillis, GetFlightsForTerminal(from, to, terminal)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForTerminalDateRange(from, to, terminal))).pipeTo(sender())

    case request: GetStateForDateRange =>
      if (PartitionedPortStateActor.isNonLegacyRequest(SDate(request.to), legacyDataCutoff)) {
        replyWithPortState(sender(), request)
      } else {
        val tempActor = context.actorOf(tempLegacyActorProps(SDate(request.to).addHours(4), request, queues, expireAfterMillis))
        replyWithLegacyPortState(tempActor, sender(), request)
      }

    case request: GetStateForTerminalDateRange =>
      replyWithPortState(sender(), request)

    case GetMinutesForTerminalDateRange(from, to, terminal) =>
      replyWithMinutesAsPortState(sender(), GetStateForTerminalDateRange(from, to, terminal))

    case GetUpdatesSince(since, start, end) =>
      replyWithUpdates(since, start, end, sender())

    case GetFlights(from, to) =>
      flightsActor.ask(GetStateForDateRange(from, to)).pipeTo(sender())

    case GetFlightsForTerminal(from, to, terminal) =>
      flightsActor.ask(GetStateForTerminalDateRange(from, to, terminal)).pipeTo(sender())
  }

  override def receive: Receive = processMessage orElse {
    case unexpected => log.warn(s"Got unexpected: ${unexpected.getClass}")
  }
}
