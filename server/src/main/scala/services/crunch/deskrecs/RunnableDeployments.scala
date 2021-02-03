package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import drt.shared.CrunchApi._
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


object RunnableDeployments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(portStateActor: ActorRef,
            queuesActor: ActorRef,
            staffActor: ActorRef,
            staffToDeskLimits: StaffToDeskLimits,
            crunchPeriodStartMillis: SDateLike => SDateLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
            minutesToCrunch: Int,
            portDeskRecs: PortDesksAndWaitsProviderLike)
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
            .map { dayToCrunch =>
              val firstMinute = crunchPeriodStartMillis(SDate(dayToCrunch))
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
                  (firstMillis, lastMillis, queues, availableStaff(staff, terminals, firstMillis, lastMillis))
                })
            }
            .map {
              case (firstMillis, lastMillis, queues, availableStaffByTerminal) =>
                val minuteMillis = firstMillis to lastMillis by 60000
                log.info(s"Simulating ${minuteMillis.length} minutes (${SDate(firstMillis).toISOString()} to ${SDate(lastMillis).toISOString()})")
                val startTime = System.currentTimeMillis()
                val deskLimitsByTerminal: Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff] = staffToDeskLimits(availableStaffByTerminal)
                val workload = queues.minutes.map(_.toMinute)
                  .map { minute => (minute.key, LoadMinute(minute)) }
                  .toMap
                val simulationMinutes = portDeskRecs.loadsToSimulations(minuteMillis, workload, deskLimitsByTerminal)
                val timeTaken = System.currentTimeMillis() - startTime
                if (timeTaken > 1000) {
                  log.warn(s"Simulation took ${timeTaken}ms")
                }

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

  private def availableStaff(staff: MinutesContainer[StaffMinute, TM],
                             terminals: Iterable[Terminal],
                             firstMillis: MillisSinceEpoch,
                             lastMillis: MillisSinceEpoch): Map[Terminal, List[Int]] =
    allMinutesForPeriod(terminals, firstMillis, lastMillis, staff.indexed)
      .groupBy(_.terminal)
      .map {
        case (t, minutes) =>
          val availableByMinute = minutes.toList.sortBy(_.minute).map(_.availableAtPcp)
          (t, availableByMinute)
      }

  private def allMinutesForPeriod(terminals: Iterable[Terminal],
                                  firstMillis: MillisSinceEpoch,
                                  lastMillis: MillisSinceEpoch,
                                  indexedStaff: Map[TM, StaffMinute]): Iterable[StaffMinute] =
    for {
      terminal <- terminals
      minute <- firstMillis to lastMillis by MilliTimes.oneMinuteMillis
    } yield {
      indexedStaff.getOrElse(TM(terminal, minute), StaffMinute(terminal, minute, 0, 0, 0, None))
    }

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
            portDeskRecs: PortDesksAndWaitsProviderLike)
           (implicit ec: ExecutionContext, mat: Materializer): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = {

    RunnableDeployments(portStateActor, queuesActor, staffActor, staffToDeskLimits, crunchPeriodStart, maxDesksProviders, minutesToCrunch, portDeskRecs).run()
  }
}
