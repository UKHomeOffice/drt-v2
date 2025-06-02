package actors

import actors.daily._
import actors.routing.minutes.MinutesActorLike.MinutesLookup
import actors.routing.minutes.{QueueLoadsMinutesActor, QueueMinutesRouterActor, StaffMinutesRouterActor}
import drt.shared.CrunchApi._
import drt.shared.TM
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait MinuteLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val now: () => SDateLike
  val expireAfterMillis: Int
  val requestAndTerminateActor: ActorRef

  val updatePassengerMinutes: ((Terminal, UtcDate), MinutesContainer[PassengersMinute, TQM]) => Future[Set[TerminalUpdateRequest]] =
    (terminalDate: (Terminal, UtcDate), container: MinutesContainer[PassengersMinute, TQM]) => {
      val (terminal, date) = terminalDate
      val actor = system.actorOf(TerminalDayQueueLoadsActor.props(terminal, date, now))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[Set[TerminalUpdateRequest]]
    }

  def updateCrunchMinutes(updateLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Unit]): ((Terminal, UtcDate), MinutesContainer[CrunchMinute, TQM]) => Future[Set[TerminalUpdateRequest]] =
    (terminalDate: (Terminal, UtcDate), container: MinutesContainer[CrunchMinute, TQM]) => {
      val (terminal, date) = terminalDate
      val actor = system.actorOf(TerminalDayQueuesActor.props(Option(updateLiveView))(terminal, date, now))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[Set[TerminalUpdateRequest]]
    }

  val updateStaffMinutes: ((Terminal, UtcDate), MinutesContainer[StaffMinute, TM]) => Future[Set[TerminalUpdateRequest]] =
    (terminalDate: (Terminal, UtcDate), container: MinutesContainer[StaffMinute, TM]) => {
      val (terminal, date) = terminalDate
      val actor = system.actorOf(TerminalDayStaffActor.props(terminal, date, now))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[Set[TerminalUpdateRequest]]
    }

  val queuesLoadsLookup: MinutesLookup[PassengersMinute, TQM] =
    lookup[PassengersMinute, TQM](TerminalDayQueueLoadsActor.props, TerminalDayQueueLoadsActor.propsPointInTime)
  val queuesLookup: MinutesLookup[CrunchMinute, TQM] =
    lookup[CrunchMinute, TQM](TerminalDayQueuesActor.props(None), TerminalDayQueuesActor.propsPointInTime)
  val staffLookup: MinutesLookup[StaffMinute, TM] =
    lookup[StaffMinute, TM](TerminalDayStaffActor.props, TerminalDayStaffActor.propsPointInTime)

  def lookup[A, B <: WithTimeAccessor]: ((Terminal, UtcDate, () => SDateLike) => Props, (Terminal, UtcDate, () => SDateLike, MillisSinceEpoch) => Props) => MinutesLookup[A, B] = {
    (nonPitProps: (Terminal, UtcDate, () => SDateLike) => Props, pitProps: (Terminal, UtcDate, () => SDateLike, MillisSinceEpoch) => Props) =>
    (terminalDate: (Terminal, UtcDate), maybePit: Option[MillisSinceEpoch]) => {
      val (terminal, date) = terminalDate
      val props = maybePit match {
        case None => nonPitProps(terminal, date, now)
        case Some(pointInTime) => pitProps(terminal, date, now, pointInTime)
      }
      val actor = system.actorOf(props)
      requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[Option[MinutesContainer[A, B]]]
    }
  }

  def queueLoadsMinutesActor: ActorRef

  def queueMinutesRouterActor: ActorRef

  def staffMinutesRouterActor: ActorRef
}

case class MinuteLookups(now: () => SDateLike,
                         expireAfterMillis: Int,
                         terminals: LocalDate => Seq[Terminal],
                         updateLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Unit],
                        )
                        (implicit val ec: ExecutionContext, val system: ActorSystem) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "minutes-lookup-kill-actor")

  override val queueLoadsMinutesActor: ActorRef = system.actorOf(Props(new QueueLoadsMinutesActor(terminals, queuesLoadsLookup, updatePassengerMinutes)))

  override val queueMinutesRouterActor: ActorRef = system.actorOf(Props(new QueueMinutesRouterActor(terminals, queuesLookup, updateCrunchMinutes(updateLiveView))))

  override val staffMinutesRouterActor: ActorRef = system.actorOf(Props(new StaffMinutesRouterActor(terminals, staffLookup, updateStaffMinutes)))
}
