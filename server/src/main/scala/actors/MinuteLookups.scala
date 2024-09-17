package actors

import actors.daily._
import actors.routing.minutes.MinutesActorLike.MinutesLookup
import actors.routing.minutes.{QueueLoadsMinutesActor, QueueMinutesRouterActor, StaffMinutesRouterActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.{TM, TQM}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait MinuteLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val now: () => SDateLike
  val expireAfterMillis: Int
  val queuesByTerminal: Map[Terminal, Seq[Queue]]
  val requestAndTerminateActor: ActorRef

  val updatePassengerMinutes: ((Terminal, UtcDate), MinutesContainer[PassengersMinute, TQM]) => Future[Set[TerminalUpdateRequest]] =
    (terminalDate: (Terminal, UtcDate), container: MinutesContainer[PassengersMinute, TQM]) => {
      val (terminal, date) = terminalDate
//      println(s"\n\n!terminal $terminal, date $date, ${container.minutes.count(_.toMinute.passengers.sum > 0)} non-zero pax load minutes ${container.minutes.groupBy(_.terminal).map(x => s"${x._1}: ${x._2.size}").mkString(", ")}\n\n")
      val actor = system.actorOf(TerminalDayQueueLoadsActor.props(terminal, date, now))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[Set[TerminalUpdateRequest]]
    }

  val updateCrunchMinutes: ((Terminal, UtcDate), MinutesContainer[CrunchMinute, TQM]) => Future[Set[TerminalUpdateRequest]] =
    (terminalDate: (Terminal, UtcDate), container: MinutesContainer[CrunchMinute, TQM]) => {
      val (terminal, date) = terminalDate
      val actor = system.actorOf(TerminalDayQueuesActor.props(terminal, date, now))
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
    lookup[CrunchMinute, TQM](TerminalDayQueuesActor.props, TerminalDayQueuesActor.propsPointInTime)
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
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                        )
                        (implicit val ec: ExecutionContext, val system: ActorSystem) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "minutes-lookup-kill-actor")

  override val queueLoadsMinutesActor: ActorRef = system.actorOf(Props(new QueueLoadsMinutesActor(queuesByTerminal.keys, queuesLoadsLookup, updatePassengerMinutes)))

  override val queueMinutesRouterActor: ActorRef = system.actorOf(Props(new QueueMinutesRouterActor(queuesByTerminal.keys, queuesLookup, updateCrunchMinutes)))

  override val staffMinutesRouterActor: ActorRef = system.actorOf(Props(new StaffMinutesRouterActor(queuesByTerminal.keys, staffLookup, updateStaffMinutes)))
}
