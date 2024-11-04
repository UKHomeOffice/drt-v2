package actors

import actors.daily._
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.{StatusReply, ask, pipe}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.actor.acking.Acking
import uk.gov.homeoffice.drt.actor.acking.Acking.AckingAsker
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.arrivals.{FlightsWithSplits, WithTimeAccessor}
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object PartitionedPortStateActor {
  def queueUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayQueuesUpdatesActor(day.getFullYear, day.getMonth, day.getDate, terminal, now, journalType))
    }

  def staffUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayStaffUpdatesActor(day.getFullYear, day.getMonth, day.getDate, terminal, now, journalType))
    }

  def flightUpdatesProps(now: () => SDateLike, journalType: StreamingJournalLike): (Terminal, SDateLike) => Props =
    (terminal: Terminal, day: SDateLike) => {
      Props(new TerminalDayFlightUpdatesActor(day.getFullYear, day.getMonth, day.getDate, terminal, now, journalType))
    }

  type QueueMinutesRequester = PortStateRequest => Future[MinutesContainer[CrunchMinute, TQM]]

  type StaffMinutesRequester = PortStateRequest => Future[MinutesContainer[StaffMinute, TM]]

  type FlightsRequester = PortStateRequest => Future[Source[(UtcDate, FlightsWithSplits), NotUsed]]

  private type FlightUpdatesRequester = PortStateRequest => Future[FlightUpdatesAndRemovals]

  type PortStateUpdatesRequester = (MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, ActorRef) => Future[Option[PortStateUpdates]]

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
    request => actor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]].recoverWith {
      case t => throw new Exception(s"Error receiving FlightsWithSplits from the flights actor, for request $request", t)
    }

  private def requestFlightUpdatesFn(actor: ActorRef)
                                    (implicit timeout: Timeout, ec: ExecutionContext): FlightUpdatesRequester =
    request => actor.ask(request).mapTo[FlightUpdatesAndRemovals].recoverWith {
      case t => throw new Exception(s"Error receiving FlightsWithSplits from the flights actor, for request $request", t)
    }

  def replyWithUpdatesFn(flights: FlightUpdatesRequester, queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                        (implicit ec: ExecutionContext): PortStateUpdatesRequester =
    (flightsSince: MillisSinceEpoch, queuesSince: MillisSinceEpoch, staffSince: MillisSinceEpoch, start: MillisSinceEpoch, end: MillisSinceEpoch, replyTo: ActorRef) => {
      combineToPortStateUpdates(
        flights(GetFlightUpdatesSince(flightsSince, start, end)),
        queueMins(GetMinuteUpdatesSince(queuesSince, start, end)),
        staffMins(GetMinuteUpdatesSince(staffSince, start, end)),
      ).pipeTo(replyTo)
    }

  def replyWithPortStateFn(flights: FlightsRequester, queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                          (implicit ec: ExecutionContext, mat: Materializer): PortStateRequester = (replyTo: ActorRef, request: PortStateRequest) =>
    combineToPortState(
      flights(request),
      queueMins(request),
      staffMins(request)
    ).pipeTo(replyTo)

  private def replyWithMinutesAsPortStateFn(queueMins: QueueMinutesRequester, staffMins: StaffMinutesRequester)
                                           (implicit ec: ExecutionContext, mat: Materializer): PortStateRequester =
    replyWithPortStateFn(_ => Future(Source(List[(UtcDate, FlightsWithSplits)]())), queueMins, staffMins)

  private def combineToPortStateUpdates(eventualFlightsDiff: Future[FlightUpdatesAndRemovals],
                                        eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                                        eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]])
                                       (implicit ec: ExecutionContext): Future[Option[PortStateUpdates]] =
    for {
      flightsDiff <- eventualFlightsDiff
      queueMinutes <- eventualQueueMinutes
      staffMinutes <- eventualStaffMinutes
    } yield {
      val cms = queueMinutes.minutes.map(_.toMinute)
      val sms = staffMinutes.minutes.map(_.toMinute)
      if (flightsDiff.nonEmpty || cms.nonEmpty || sms.nonEmpty)
        Option(PortStateUpdates(flightsDiff.latestUpdateMillis, queueMinutes.latestUpdateMillis, staffMinutes.latestUpdateMillis, flightsDiff, cms, sms))
      else None
    }

  private def combineToPortState(flightsStream: Future[Source[(UtcDate, FlightsWithSplits), NotUsed]],
                                 eventualQueueMinutes: Future[MinutesContainer[CrunchMinute, TQM]],
                                 eventualStaffMinutes: Future[MinutesContainer[StaffMinute, TM]])
                                (implicit ec: ExecutionContext, mat: Materializer): Future[PortState] = {
    val eventualFlights = flightsStream
      .flatMap(source => source
        .log(getClass.getName)
        .runWith(Sink.seq)
        .map(x => x.foldLeft(FlightsWithSplits.empty)(_ ++ _._2)))
    for {
      flights <- eventualFlights
      queueMinutes <- eventualQueueMinutes
      staffMinutes <- eventualStaffMinutes
    } yield {
      PortState(flights.flights.values, queueMinutes.minutes.map(_.toMinute), staffMinutes.minutes.map(_.toMinute))
    }
  }

  trait DateRangeMillisLike {
    val from: MillisSinceEpoch
    val to: MillisSinceEpoch
  }

  trait UtcDateRangeLike {
    val start: UtcDate
    val end: UtcDate
  }

  trait PortStateRequest extends DateRangeMillisLike

  trait FlightsRequest extends DateRangeMillisLike

  trait TerminalRequest extends DateRangeMillisLike {
    val terminal: Terminal
  }

  case class GetFlightUpdatesSince(since: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest
  case class GetMinuteUpdatesSince(since: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest

  case class GetUpdatesSince(flights: MillisSinceEpoch, queues: MillisSinceEpoch, staff: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest

  case class PointInTimeQuery(pointInTime: MillisSinceEpoch, query: DateRangeMillisLike) extends PortStateRequest {
    override val from: MillisSinceEpoch = query.from
    override val to: MillisSinceEpoch = query.to
  }

  case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch) extends FlightsRequest

  case class GetFlightsForTerminals(from: MillisSinceEpoch, to: MillisSinceEpoch, terminals: Iterable[Terminal]) extends FlightsRequest

  case class GetFlightsForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends FlightsRequest with TerminalRequest

  case class GetStateForDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch) extends PortStateRequest

  case class GetStateForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends PortStateRequest with TerminalRequest

  case class GetMinutesForTerminalDateRange(from: MillisSinceEpoch, to: MillisSinceEpoch, terminal: Terminal) extends PortStateRequest with TerminalRequest

}

class PartitionedPortStateActor(flightsRouterActor: ActorRef,
                                queuesRouterActor: ActorRef,
                                staffRouterActor: ActorRef,
                                queueUpdatesActor: ActorRef,
                                staffUpdatesActor: ActorRef,
                                flightUpdatesActor: ActorRef,
                                val now: () => SDateLike,
                                val queues: Map[Terminal, Seq[Queue]],
                                val journalType: StreamingJournalLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PartitionedPortStateActor._

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(90 seconds)

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

  private val requestStaffMinuteUpdates: StaffMinutesRequester = requestStaffMinutesFn(staffUpdatesActor)
  private val requestQueueMinuteUpdates: QueueMinutesRequester = requestQueueMinutesFn(queueUpdatesActor)
  private val requestFlightUpdates: FlightUpdatesRequester = requestFlightUpdatesFn(flightUpdatesActor)
  private val requestStaffMinutes: StaffMinutesRequester = requestStaffMinutesFn(staffRouterActor)
  val requestQueueMinutes: QueueMinutesRequester = requestQueueMinutesFn(queuesRouterActor)
  private val requestFlights: FlightsRequester = requestFlightsFn(flightsRouterActor)
  val replyWithUpdates: PortStateUpdatesRequester = replyWithUpdatesFn(requestFlightUpdates, requestQueueMinuteUpdates, requestStaffMinuteUpdates)
  val replyWithPortState: PortStateRequester = replyWithPortStateFn(requestFlights, requestQueueMinutes, requestStaffMinutes)
  private val replyWithMinutesAsPortState: PortStateRequester = replyWithMinutesAsPortStateFn(requestQueueMinutes, requestStaffMinutes)
  val askThenAck: AckingAsker = Acking.askThenAck

  private def containsQueueTypeMinutes[A, B <: WithTimeAccessor](mins: MinutesContainer[A, B]): Boolean =
    mins.minutes.headOption.exists(_.toMinute.isInstanceOf[CrunchMinute])

  private def containsStaffTypeMinutes[A, B <: WithTimeAccessor](mins: MinutesContainer[A, B]): Boolean =
    mins.minutes.headOption.exists(_.toMinute.isInstanceOf[StaffMinute])

  def processMessage: Receive = {
    case StreamInitialized => sender() ! StatusReply.Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case updates: FlightUpdates =>
      askThenAck(flightsRouterActor, updates, sender())

    case noUpdates: PortStateMinutes[_, _] if noUpdates.isEmpty =>
      sender() ! StatusReply.Ack

    case someQueueUpdates: MinutesContainer[CrunchMinute, TQM] if containsQueueTypeMinutes(someQueueUpdates) =>
      val replyTo = sender()
      askThenAck(queuesRouterActor, someQueueUpdates, replyTo)

    case someStaffUpdates: MinutesContainer[StaffMinute, TM] if containsStaffTypeMinutes(someStaffUpdates) =>
      val replyTo = sender()
      askThenAck(staffRouterActor, someStaffUpdates, replyTo)

    case GetUpdatesSince(flights, queues, staff, from, to) => replyWithUpdates(flights, queues, staff, from, to, sender())

    case pitRequest@PointInTimeQuery(_, _: GetStateForDateRange) =>
      replyWithPortState(sender(), pitRequest)

    case pitRequest@PointInTimeQuery(_, _: GetStateForTerminalDateRange) =>
      replyWithPortState(sender(), pitRequest)

    case pitRequest@PointInTimeQuery(_, _: GetMinutesForTerminalDateRange) =>
      replyWithMinutesAsPortState(sender(), pitRequest)

    case pitRequest@PointInTimeQuery(_, _: FlightsRequest) =>
      flightsRouterActor.ask(pitRequest).pipeTo(sender())

    case request: GetStateForDateRange =>
      replyWithPortState(sender(), request)

    case request: GetStateForTerminalDateRange =>
      replyWithPortState(sender(), request)

    case request: GetMinutesForTerminalDateRange =>
      replyWithMinutesAsPortState(sender(), request)

    case request: FlightsRequest =>
      flightsRouterActor.ask(request).pipeTo(sender())
  }

  override def receive: Receive = processMessage orElse {
    case unexpected => log.warn(s"Got unexpected: ${unexpected.getClass}")
  }
}
