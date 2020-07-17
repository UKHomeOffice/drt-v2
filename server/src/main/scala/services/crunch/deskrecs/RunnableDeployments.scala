package services.crunch.deskrecs

import actors.MinuteLookupsLike
import actors.acking.AckingReceiver._
import actors.minutes.{QueueMinutesActor, StaffMinutesActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps


case class MinuteLookups(system: ActorSystem,
                         now: () => SDateLike,
                         expireAfterMillis: Int,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         override val replayMaxCrunchStateMessages: Int)
                        (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val queueMinutesActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(now, queuesByTerminal.keys, queuesLookup, legacyQueuesLookup, updateCrunchMinutes)))

  override val staffMinutesActor: ActorRef = system.actorOf(Props(new StaffMinutesActor(now, queuesByTerminal.keys, staffLookup, legacyStaffLookup, updateStaffMinutes)))
}

object RunnableDeployments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(portStateActor: ActorRef,
            queuesActor: ActorRef,
            staffActor: ActorRef,
            staffToDeskLimits: StaffToDeskLimits,
            crunchPeriodStartMillis: SDateLike => SDateLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
            minutesToCrunch: Int,
            portDeskRecs: DesksAndWaitsPortProviderLike /*,
            queuesByTerminal: Map[Terminal, Seq[Queue]]*/)
           (implicit executionContext: ExecutionContext,
            timeout: Timeout = new Timeout(60 seconds)): RunnableGraph[(SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val daysSourceQueue = Source.queue[MillisSinceEpoch](1, OverflowStrategy.backpressure).async

    val graph = GraphDSL.create(
      daysSourceQueue,
      KillSwitches.single[SimulationMinutes])((_, _)) {
      implicit builder =>
        (daysSourceQueueAsync, killSwitch) =>
          val deploymentsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysSourceQueueAsync.out
            .map { min =>
              val firstMinute = crunchPeriodStartMillis(SDate(min))
              val lastMinute = firstMinute.addMinutes(minutesToCrunch - 1)
              (firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch)
            }
            .mapAsync(1) { case (firstMillis, lastMillis) =>
              queueMinutes(queuesActor, firstMillis, lastMillis)
                .map { container => (firstMillis, lastMillis, container) }
            }
            .mapAsync(1) { case (firstMillis, lastMillis, queues) =>
              staffMinutes(staffActor, firstMillis, lastMillis)
                .map(staff => {
                  val terminals = maxDesksProviders.keys
                  (firstMillis, lastMillis, queues, availableStaff(staff, terminals))
                })
            }
            .map {
              case (firstMillis, lastMillis, queues, availableStaffByTerminal) =>
                val minuteMillis = firstMillis to lastMillis by 60000

                log.info(s"Simulating ${minuteMillis.length} minutes (${SDate(firstMillis).toISOString()} to ${SDate(lastMillis).toISOString()})")
                val deskLimitsByTerminal: Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff] = staffToDeskLimits(availableStaffByTerminal)
                val workload = queues.minutes.map(_.toMinute)
                  .map { minute => (minute.key, LoadMinute(minute)) }
                  .toMap
                val simulationMinutes = portDeskRecs.loadsToSimulations(minuteMillis, workload, deskLimitsByTerminal)
                log.info(s"Finished simulation: ${simulationMinutes.size} minutes")
                SimulationMinutes(simulationMinutes.values.toList)
            } ~> killSwitch ~> deploymentsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph).addAttributes(Attributes.inputBuffer(1, 1))
  }

  private def staffMinutes(staffActor: ActorRef, firstMillis: MillisSinceEpoch, lastMillis: MillisSinceEpoch)
                          (implicit timeout: Timeout): Future[MinutesContainer[StaffMinute, TM]] =
    staffActor
      .ask(GetStateForDateRange(firstMillis, lastMillis))
      .mapTo[MinutesContainer[StaffMinute, TM]]

  private def availableStaff(staff: MinutesContainer[StaffMinute, TM], terminals: Iterable[Terminal]): Map[Terminal, List[Int]] =
    terminals
      .map { terminal =>
        val minutesForTerminal = staff.minutes.filter(_.terminal == terminal)
        val availableForTerminal = minutesForTerminal.map(_.toMinute).toList.sortBy(_.minute).map(_.availableAtPcp)
        (terminal, availableForTerminal)
      }
      .toMap

  private def queueMinutes(queuesActor: ActorRef, firstMillis: MillisSinceEpoch, lastMillis: MillisSinceEpoch)
                          (implicit timeout: Timeout): Future[MinutesContainer[CrunchMinute, TQM]] =
    queuesActor
      .ask(GetStateForDateRange(firstMillis, lastMillis))
      .mapTo[MinutesContainer[CrunchMinute, TQM]]

  def start(portStateActor: ActorRef,
            queuesActor: ActorRef,
            staffActor: ActorRef,
            staffToDeskLimits: StaffToDeskLimits,
            crunchPeriodStart: SDateLike => SDateLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
            minutesToCrunch: Int,
            portDeskRecs: DesksAndWaitsPortProviderLike /*,
            queuesByTerminal: Map[Terminal, Seq[Queue]]*/)
           (implicit ec: ExecutionContext, mat: Materializer): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = {

    RunnableDeployments(portStateActor, queuesActor, staffActor, staffToDeskLimits, crunchPeriodStart, maxDesksProviders, minutesToCrunch, portDeskRecs /*, queuesByTerminal*/).run()
  }
}

case class SimulationMinute(terminal: Terminal,
                            queue: Queue,
                            minute: MillisSinceEpoch,
                            desks: Int,
                            waitTime: Int) extends SimulationMinuteLike with MinuteComparison[CrunchMinute] with MinuteLike[CrunchMinute, TQM] {
  lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

  override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
    if (existing.deployedDesks.isEmpty || existing.deployedDesks.get != desks || existing.deployedWait.isEmpty || existing.deployedWait.get != waitTime) Option(existing.copy(
      deployedDesks = Option(desks), deployedWait = Option(waitTime), lastUpdated = Option(now)
    ))
    else None

  override val lastUpdated: Option[MillisSinceEpoch] = None

  override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

  override def toMinute: CrunchMinute = CrunchMinute(
    terminal = terminal,
    queue = queue,
    minute = minute,
    paxLoad = 0,
    workLoad = 0,
    deskRec = 0,
    waitTime = 0,
    deployedDesks = Option(desks),
    deployedWait = Option(waitTime),
    lastUpdated = None)

}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateQueueMinutes {
  override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

  override def isEmpty: Boolean = minutes.isEmpty

  override def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
    val minutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, dm) =>
      addIfUpdated(portState.crunchMinutes.getByKey(dm.key), now, soFar, dm, () => dm.toUpdatedMinute(now))
    }
    portState.crunchMinutes +++= minutesDiff
    PortStateDiff(Seq(), Seq(), Seq(), minutesDiff, Seq())
  }
}
