package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{QueueUpdatesSupervisor, StaffUpdatesSupervisor, TerminalDayQueuesUpdatesActor, TerminalDayStaffUpdatesActor, UpdatesSupervisor}
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.{GetFlights, GetStateForDateRange, GetStateForTerminalDateRange, PortStateRequest}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object PartitionedPortStateActor {
  def apply(now: () => SDateLike, airportConfig: AirportConfig, journalType: StreamingJournalLike, legacyDataCutoff: SDateLike)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val lookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val flightsActor: ActorRef = system.actorOf(Props(new FlightsStateActor(now, expireAfterMillis, airportConfig.queuesByTerminal, legacyDataCutoff)))
    val queuesActor: ActorRef = lookups.queueMinutesActor
    val staffActor: ActorRef = lookups.staffMinutesActor
    system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now, airportConfig.terminals.toList, journalType)))
  }
}

trait PartitionedPortStateActorLike {
  val now: () => SDateLike
  val context: ActorContext
  val journalType: StreamingJournalLike
  val terminals: List[Terminal]

  val queueUpdatesProps: (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayQueuesUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  val staffUpdatesProps: (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayStaffUpdatesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, now, journalType))
    }

  val queueUpdatesSupervisor: ActorRef
  val staffUpdatesSupervisor: ActorRef
}

trait ProdPartitionedPortStateActor extends PartitionedPortStateActorLike {
  override val queueUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new QueueUpdatesSupervisor(now, terminals, queueUpdatesProps)), "updates-supervisor-queues")
  override val staffUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new StaffUpdatesSupervisor(now, terminals, staffUpdatesProps)), "updates-supervisor-staff")
}

class PartitionedPortStateActor(flightsActor: ActorRef,
                                queuesActor: ActorRef,
                                staffActor: ActorRef,
                                val now: () => SDateLike,
                                val terminals: List[Terminal],
                                val journalType: StreamingJournalLike) extends Actor with ProdPartitionedPortStateActor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  def processMessage: Receive = {
    case msg: SetCrunchQueueActor =>
      log.info(s"Received crunchSourceActor")
      flightsActor ! msg

    case msg: SetSimulationActor =>
      log.info(s"Received simulationSourceActor")
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

    case PointInTimeQuery(millis, request: GetStateForDateRange) =>
      replyWithPortState(sender(), PointInTimeQuery(millis, request))

    case PointInTimeQuery(millis, request: GetStateForTerminalDateRange) =>
      replyWithPortState(sender(), PointInTimeQuery(millis, request))

    case PointInTimeQuery(pitMillis, GetFlights(from, to)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForDateRange(from, to))).pipeTo(sender())

    case PointInTimeQuery(pitMillis, GetFlightsForTerminal(from, to, terminal)) =>
      flightsActor.ask(PointInTimeQuery(pitMillis, GetStateForTerminalDateRange(from, to, terminal))).pipeTo(sender())

    case request: GetStateForDateRange =>
      replyWithPortState(sender(), request)

    case request: GetStateForTerminalDateRange =>
      replyWithPortState(sender(), request)

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
    val (eventualFlights, eventualQueueMinutes, eventualStaffMinutes) = portStateComponentsRequest(GetUpdatesSince(since, start, end))

    combineToPortStateUpdates(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).pipeTo(replyTo)
  }

  def replyWithPortState(replyTo: ActorRef, request: PortStateRequest): Unit = {
    val (eventualFlights, eventualQueueMinutes, eventualStaffMinutes) = portStateComponentsRequest(request)

    combineToPortState(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).pipeTo(replyTo)
  }

  def portStateComponentsRequest(request: PortStateRequest): (Future[FlightsWithSplits], Future[MinutesContainer[CrunchMinute, TQM]], Future[MinutesContainer[StaffMinute, TM]]) = {
    val eventualFlights = flightsActor.ask(request).mapTo[FlightsWithSplits].recoverWith {
      case t => throw new Exception(s"Error receiving FlightsWithSplits from the flights actor, for request $request", t)
    }
    val eventualQueueMinutes = queueActorForRequest(request).ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the queues actors, for request $request", t)
    }
    val eventualStaffMinutes = staffActorForRequest(request).ask(request).mapTo[MinutesContainer[StaffMinute, TM]].recoverWith {
      case t => throw new Exception(s"Error receiving MinutesContainer from the staff actors, for request $request", t)
    }
    (eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
  }

  def queueActorForRequest(request: PortStateRequest): ActorRef = request match {
    case _: GetUpdatesSince => queueUpdatesSupervisor
    case _ => queuesActor
  }

  def staffActorForRequest(request: PortStateRequest): ActorRef = request match {
    case _: GetUpdatesSince => staffUpdatesSupervisor
    case _ => staffActor
  }

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
