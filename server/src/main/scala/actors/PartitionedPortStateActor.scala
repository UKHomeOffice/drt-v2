package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{TerminalDayQueuesUpdatesActor, TerminalDayStaffUpdatesActor, UpdatesSupervisor}
import actors.minutes.{QueueMinutesActor, StaffMinutesActor}
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.GetFlights

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object PartitionedPortStateActor {
  def apply(now: () => SDateLike, airportConfig: AirportConfig, journalType: StreamingJournalLike)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val lookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val flightsActor: ActorRef = system.actorOf(Props(new FlightsStateActor(Option(5000), Sizes.oneMegaByte, "crunch-live-state-actor", now, expireAfterMillis)))
    val queuesActor: ActorRef = lookups.queueMinutesActor(classOf[QueueMinutesActor])
    val staffActor: ActorRef = lookups.staffMinutesActor(classOf[StaffMinutesActor])
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
  override val queueUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new UpdatesSupervisor[CrunchMinute, TQM](now, terminals, queueUpdatesProps)))
  override val staffUpdatesSupervisor: ActorRef = context.system.actorOf(Props(new UpdatesSupervisor[StaffMinute, TM](now, terminals, staffUpdatesProps)))
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
  implicit val timeout: Timeout = new Timeout(10 seconds)

  def processMessage: Receive = {
    case msg: SetCrunchQueueActor =>
      log.info(s"Received crunchSourceActor")
      flightsActor.ask(msg)

    case msg: SetSimulationActor =>
      log.info(s"Received simulationSourceActor")
      queuesActor.ask(msg)

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

    case GetState =>
      log.warn("Ignoring GetState request (for entire state)")

    case GetPortState(start, end) =>
      log.debug(s"Received GetPortState request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      replyWithPortState(start, end, sender())

    case GetPortStateForTerminal(start, end, terminal) =>
      log.debug(s"Received GetPortStateForTerminal request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      replyWithTerminalState(start, end, terminal, sender())

    case GetUpdatesSince(since, start, end) =>
      log.info(s"Received GetUpdatesSince request since ${SDate(since).toISOString()} from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      replyWithUpdates(since, start, end, sender())

    case getFlights: GetFlights =>
      log.debug(s"Received GetFlights request from ${SDate(getFlights.from).toISOString()} to ${SDate(getFlights.to).toISOString()}")
      flightsActor forward getFlights

    case getFlightsForTerminal: GetFlightsForTerminal =>
      log.debug(s"Received GetFlightsForTerminal request from ${SDate(getFlightsForTerminal.from).toISOString()} to ${SDate(getFlightsForTerminal.to).toISOString()}")
      flightsActor forward getFlightsForTerminal
  }

  override def receive: Receive = processMessage orElse {
    case unexpected => log.warn(s"Got unexpected: ${unexpected.getClass}")
  }

  def replyWithPortState(startMillis: MillisSinceEpoch,
                         endMillis: MillisSinceEpoch,
                         replyTo: ActorRef): Future[PortState] = {
    val eventualFlights = flightsActor.ask(GetFlights(startMillis, endMillis)).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queuesActor.ask(GetPortState(startMillis, endMillis)).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffActor.ask(GetPortState(startMillis, endMillis)).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortState(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.pipeTo(replyTo)
  }

  def replyWithUpdates(since: MillisSinceEpoch,
                       start: MillisSinceEpoch,
                       end: MillisSinceEpoch,
                       replyTo: ActorRef): Future[Option[PortStateUpdates]] = {
    val updatesRequest = GetUpdatesSince(since, start, end)
    log.info(s"Sending GetUpdatesSince to flights, queue & staff actors")
    val eventualFlights = flightsActor.ask(updatesRequest).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queueUpdatesSupervisor.ask(updatesRequest).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffUpdatesSupervisor.ask(updatesRequest).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortStateUpdates(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.pipeTo(replyTo)
  }

  def replyWithTerminalState(start: MillisSinceEpoch,
                             end: MillisSinceEpoch,
                             terminal: Terminal,
                             replyTo: ActorRef): Future[PortState] = {
    val eventualFlights = flightsActor.ask(GetFlightsForTerminal(start, end, terminal)).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queuesActor.ask(GetPortStateForTerminal(start, end, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffActor.ask(GetPortStateForTerminal(start, end, terminal)).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortState(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.pipeTo(replyTo)
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
