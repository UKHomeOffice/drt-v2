package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{AskableActorRef, pipe}
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
  def apply(now: () => SDateLike, airportConfig: AirportConfig)(implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val lookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val flightsActor: ActorRef = system.actorOf(Props(new FlightsStateActor(None, Sizes.oneMegaByte, "crunch-live-state-actor", airportConfig.queuesByTerminal, now, expireAfterMillis)))
    val queuesActor: ActorRef = lookups.queueMinutesActor(classOf[MinutesActor[CrunchMinute, TQM]])
    val staffActor: ActorRef = lookups.staffMinutesActor(classOf[MinutesActor[StaffMinute, TM]])
    system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now)))
  }
}

class PartitionedPortStateActor(flightsActor: AskableActorRef,
                                queuesActor: AskableActorRef,
                                staffActor: AskableActorRef,
                                now: () => SDateLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val timeout: Timeout = new Timeout(10 seconds)

  def processMessage: Receive = {
    case msg: SetCrunchActor =>
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
      log.debug(s"Received GetUpdatesSince request since ${SDate(since).toISOString()} from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      replyWithUpdates(since, start, end, sender())

    case GetFlights(start, end) =>
      log.debug(s"Received GetFlights request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      flightsActor.ask(GetFlights(start, end)).mapTo[FlightsWithSplits].pipeTo(sender())
  }

  override def receive: Receive = processMessage orElse {
    case unexpected => log.warn(s"Got unexpected: $unexpected")
  }

  def replyWithPortState(start: MillisSinceEpoch,
                         end: MillisSinceEpoch,
                         replyTo: ActorRef): Future[Option[PortState]] = {
    val eventualFlights = flightsActor.ask(GetFlights(start, end)).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queuesActor.ask(GetPortState(start, end)).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffActor.ask(GetPortState(start, end)).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortState(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.map(Option(_)).pipeTo(replyTo)
  }

  def replyWithUpdates(since: MillisSinceEpoch,
                       start: MillisSinceEpoch,
                       end: MillisSinceEpoch,
                       replyTo: ActorRef): Future[Option[PortStateUpdates]] = {
    val updatesRequest = GetUpdatesSince(since, start, end)
    val eventualFlights = flightsActor.ask(updatesRequest).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queuesActor.ask(updatesRequest).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffActor.ask(updatesRequest).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortStateUpdates(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.map(Option(_)).pipeTo(replyTo)
  }

  def replyWithTerminalState(start: MillisSinceEpoch,
                             end: MillisSinceEpoch,
                             terminal: Terminal,
                             replyTo: ActorRef): Future[Option[PortState]] = {
    val eventualFlights = flightsActor.ask(GetPortStateForTerminal(start, end, terminal)).mapTo[FlightsWithSplits]
    val eventualQueueMinutes = queuesActor.ask(GetStateByTerminalDateRange(terminal, SDate(start), SDate(end))).mapTo[MinutesContainer[CrunchMinute, TQM]]
    val eventualStaffMinutes = staffActor.ask(GetStateByTerminalDateRange(terminal, SDate(start), SDate(end))).mapTo[MinutesContainer[StaffMinute, TM]]
    val eventualPortState = combineToPortState(eventualFlights, eventualQueueMinutes, eventualStaffMinutes)
    eventualPortState.map(Option(_)).pipeTo(replyTo)
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
                                eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]]): Future[PortStateUpdates] =
    stateAsTuple(eventualFlights, eventualQueueMinutes, eventualStaffMinutes).map {
      case (fs, cms, sms) =>
        val latestUpdateMillis = fs.map(_.lastUpdated.getOrElse(0L)) ++ cms.map(_.lastUpdated.getOrElse(0L)) ++ sms.map(_.lastUpdated.getOrElse(0L)) match {
          case noUpdates if noUpdates.isEmpty => 0L
          case millis => millis.max
        }
        PortStateUpdates(latestUpdateMillis, fs.toSet, cms.toSet, sms.toSet)
    }

  def askThenAck(message: Any, replyTo: ActorRef, actor: AskableActorRef): Unit =
    actor.ask(message).foreach(_ => replyTo ! Ack)
}
