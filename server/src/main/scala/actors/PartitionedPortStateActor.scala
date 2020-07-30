package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor.DateRangeLike
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

  def requestStaffMinutesFn(actor: ActorRef)
                           (implicit timeout: Timeout, ec: ExecutionContext): PortStateRequest => Future[MinutesContainer[StaffMinute, TM]] =
    request => actor.ask(request).mapTo[MinutesContainer[StaffMinute, TM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the staff actors, for request $request", t)
    }

  def requestQueueMinutesFn(actor: ActorRef)
                           (implicit timeout: Timeout, ec: ExecutionContext): PortStateRequest => Future[MinutesContainer[CrunchMinute, TQM]] =
    request => actor.ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the queues actors, for request $request", t)
    }

  def requestFlightsFn(actor: ActorRef)
                      (implicit timeout: Timeout, ec: ExecutionContext): PortStateRequest => Future[FlightsWithSplits] =
    request => actor.ask(request).mapTo[FlightsWithSplits].recoverWith {
      case t => throw new Exception(s"Error receiving FlightsWithSplits from the flights actor, for request $request", t)
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
                                tempLegacyActorProps: (SDateLike, DateRangeLike, Map[Terminal, Seq[Queue]], Int) => Props) extends Actor /*with ProdPartitionedPortStateActor*/ {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PartitionedPortStateActor._

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

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
      askThenAck(flightsWithSplits, replyTo, flightsActor)

    case noUpdates: PortStateMinutes[_, _] if noUpdates.isEmpty =>
      sender() ! Ack

    case someQueueUpdates: PortStateQueueMinutes =>
      val replyTo = sender()
      askThenAck(someQueueUpdates.asContainer, replyTo, queuesActor)

    case someStaffUpdates: PortStateStaffMinutes =>
      val replyTo = sender()
      askThenAck(someStaffUpdates.asContainer, replyTo, staffActor)

    case GetUpdatesSince(since, from, to) => replyWithUpdates(since, from, to, sender())

    case PointInTimeQuery(millis, request: GetStateForDateRange) =>
      if (PartitionedPortStateActor.isNonLegacyRequest(SDate(millis), legacyDataCutoff))
        replyWithPortState(sender(), PointInTimeQuery(millis, request))
      else
        replyWithLegacyPortState(sender(), SDate(millis), request)

    case PointInTimeQuery(millis, request: GetStateForTerminalDateRange) =>
      replyWithPortState(sender(), PointInTimeQuery(millis, request))

    case PointInTimeQuery(millis, GetMinutesForTerminalDateRange(from, to, terminal)) =>
      replyWithMinutesAsPortState(sender(), PointInTimeQuery(millis, GetStateForTerminalDateRange(from, to, terminal)))

    case PointInTimeQuery(pitMillis, GetFlights(from, to)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForDateRange(from, to))).pipeTo(sender())

    case PointInTimeQuery(pitMillis, GetFlightsForTerminal(from, to, terminal)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForTerminalDateRange(from, to, terminal))).pipeTo(sender())

    case request: GetStateForDateRange =>
      if (PartitionedPortStateActor.isNonLegacyRequest(SDate(request.to), legacyDataCutoff))
        replyWithPortState(sender(), request)
      else
        replyWithLegacyPortState(sender(), pointInTime = SDate(request.to).addHours(4), request)

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

  def replyWithUpdates(since: MillisSinceEpoch,
                       start: MillisSinceEpoch,
                       end: MillisSinceEpoch,
                       replyTo: ActorRef): Future[Option[PortStateUpdates]] = {
    val request = GetUpdatesSince(since, start, end)
    combineToPortStateUpdates(
      requestFlights(request),
      requestQueueMinuteUpdates(request),
      requestStaffMinuteUpdates(request)
    ).pipeTo(replyTo)
  }

  def replyWithPortState(replyTo: ActorRef, request: PortStateRequest): Unit =
    combineToPortState(
      requestFlights(request),
      requestQueueMinutes(request),
      requestStaffMinutes(request)
    ).pipeTo(replyTo)

  def replyWithMinutesAsPortState(replyTo: ActorRef, request: PortStateRequest): Unit =
    combineToPortState(
      Future(FlightsWithSplits.empty),
      requestQueueMinutes(request),
      requestStaffMinutes(request)
    ).pipeTo(replyTo)

  lazy val requestStaffMinuteUpdates: PortStateRequest => Future[MinutesContainer[StaffMinute, TM]] = requestStaffMinutesFn(staffUpdatesActor)
  lazy val requestQueueMinuteUpdates: PortStateRequest => Future[MinutesContainer[CrunchMinute, TQM]] = requestQueueMinutesFn(queueUpdatesActor)
  val requestStaffMinutes: PortStateRequest => Future[MinutesContainer[StaffMinute, TM]] = requestStaffMinutesFn(staffActor)
  val requestQueueMinutes: PortStateRequest => Future[MinutesContainer[CrunchMinute, TQM]] = requestQueueMinutesFn(queuesActor)
  val requestFlights: PortStateRequest => Future[FlightsWithSplits] = requestFlightsFn(flightsActor)

  def replyWithLegacyPortState(replyTo: ActorRef, pointInTime: SDateLike, message: DateRangeLike): Unit = {
    val tempActor = tempPointInTimeActor(pointInTime, message)
    killActor
      .ask(RequestAndTerminate(tempActor, message))
      .pipeTo(replyTo)
  }

  def tempPointInTimeActor(pointInTime: SDateLike, message: DateRangeLike): ActorRef =
    context.actorOf(tempLegacyActorProps(pointInTime, message, queues, expireAfterMillis))

  def stateAsTuple(eventualFlights: Future[FlightsWithSplits],
                   eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                   eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]]): Future[(Iterable[ApiFlightWithSplits], Iterable[CrunchMinute], Iterable[StaffMinute])] =
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

  def combineToPortState(eventualFlights: Future[FlightsWithSplits],
                         eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                         eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]]): Future[PortState] =
    stateAsTuple(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).map {
      case (fs, cms, sms) => PortState(fs, cms, sms)
    }

  def combineToPortStateUpdates(eventualFlights: Future[FlightsWithSplits],
                                eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                                eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]]): Future[Option[PortStateUpdates]] =
    stateAsTuple(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).map {
      case (fs, cms, sms) =>
        fs.map(_.lastUpdated.getOrElse(0L)) ++ cms.map(_.lastUpdated.getOrElse(0L)) ++ sms.map(_.lastUpdated.getOrElse(0L)) match {
          case noUpdates if noUpdates.isEmpty =>
            None
          case millis =>
            Option(PortStateUpdates(millis.max, fs.toSet, cms.toSet, sms.toSet))
        }
    }

  def askThenAck(message: Any, replyTo: ActorRef, actor: ActorRef): Unit =
    actor.ask(message).foreach { _ =>
      replyTo ! Ack
    }
}
