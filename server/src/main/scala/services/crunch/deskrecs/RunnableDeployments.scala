package services.crunch.deskrecs

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import services.graphstages.Crunch.LoadMinute
import services.graphstages.SimulationMinute

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDeployments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(queuesActor: ActorRef,
            staffActor: ActorRef,
            staffToDeskLimits: StaffToDeskLimits,
            crunchPeriodStartMillis: SDateLike => SDateLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
            minutesToCrunch: Int,
            portDeskRecs: DesksAndWaitsPortProvider)
           (implicit executionContext: ExecutionContext,
            timeout: Timeout = new Timeout(60 seconds)): RunnableGraph[(SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val daysSourceQueue = Source.queue[MillisSinceEpoch](1, OverflowStrategy.backpressure).async

    val graph = GraphDSL.create(
      daysSourceQueue,
      KillSwitches.single[Map[TQM, SimulationMinute]])((_, _)) {
      implicit builder =>
        (daysSourceQueueAsync, killSwitch) =>
          val deploymentsSink = builder.add(Sink.actorRefWithAck(queuesActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

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
                .map(staff => (firstMillis, lastMillis, queues, availableStaff(staff)))
            }
            .map { case (firstMillis, lastMillis, queues, availableStaffByTerminal) =>
              val minuteMillis = firstMillis to lastMillis by 60000

              log.info(s"Simulating ${minuteMillis.length} minutes (${SDate(firstMillis).toISOString()} to ${SDate(lastMillis).toISOString()})")
              val deskLimitsByTerminal: Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff] = staffToDeskLimits(availableStaffByTerminal)
              val workload = queues.minutes.map(_.toMinute)
                .map { minute => (minute.key, LoadMinute(minute)) }
                .toMap
              portDeskRecs.loadsToSimulations(minuteMillis, workload, deskLimitsByTerminal)
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

  private def availableStaff(staff: MinutesContainer[StaffMinute, TM]): Map[Terminal, List[Int]] =
    staff.minutes
      .map(_.toMinute)
      .groupBy(_.terminal)
      .map {
        case (terminal, minutes) => (terminal, minutes.toList.sortBy(_.minute).map(_.availableAtPcp))
      }

  private def queueMinutes(queuesActor: ActorRef, firstMillis: MillisSinceEpoch, lastMillis: MillisSinceEpoch)
                          (implicit timeout: Timeout): Future[MinutesContainer[CrunchMinute, TQM]] =
    queuesActor
      .ask(GetStateForDateRange(firstMillis, lastMillis))
      .mapTo[MinutesContainer[CrunchMinute, TQM]]

  def start(queuesActor: ActorRef,
            staffActor: ActorRef,
            staffToDeskLimits: StaffToDeskLimits,
            crunchPeriodStart: SDateLike => SDateLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
            minutesToCrunch: Int,
            portDeskRecs: DesksAndWaitsPortProvider)
           (implicit ec: ExecutionContext, mat: Materializer): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = {

    RunnableDeployments(queuesActor, staffActor, staffToDeskLimits, crunchPeriodStart, maxDesksProviders, minutesToCrunch, portDeskRecs).run()
  }
}

