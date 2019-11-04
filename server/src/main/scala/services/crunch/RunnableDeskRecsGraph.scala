package services.crunch

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.{Crunch, WorkloadCalculator}
import services.{OptimizerConfig, OptimizerCrunchResult, SDate, TryCrunch}

import scala.collection.immutable.{Map, SortedMap, SortedSet}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object RunnableDeskRecsGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(MilliDate(adjustedMinute.millisSinceEpoch)).addMinutes(offsetMinutes)
  }

  def apply(portStateActor: ActorRef,
            minutesToCrunch: Int,
            crunch: TryCrunch,
            airportConfig: AirportConfig
           )(implicit executionContext: ExecutionContext): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    implicit val timeout: Timeout = new Timeout(10 seconds)

    val askablePortStateActor: AskableActorRef = portStateActor

    val crunchPeriodStartMillis: SDateLike => SDateLike = crunchStartWithOffset(airportConfig.crunchOffsetMinutes)

    def flightLoadMinutes(incomingFlights: FlightsWithSplits): SplitMinutes = {
      val uniqueFlights: Iterable[ApiFlightWithSplits] = incomingFlights
        .flightsToUpdate
        .sortBy {
          _.apiFlight.ActPax.getOrElse(0)
        }
        .map { fws => (CodeShareKeyOrderedBySchedule(fws), fws) }
        .toMap.values

      val minutes = new SplitMinutes

      uniqueFlights
        .filter(fws => !isCancelled(fws) && airportConfig.defaultProcessingTimes.contains(fws.apiFlight.Terminal))
        .foreach { incoming =>
          val procTimes = airportConfig.defaultProcessingTimes(incoming.apiFlight.Terminal)
          minutes ++= WorkloadCalculator.flightToFlightSplitMinutes(incoming, procTimes, Map(), false)
        }

      minutes
    }

    def isCancelled(f: ApiFlightWithSplits): Boolean = {
      val cancelled = f.apiFlight.Status == "Cancelled"
      if (cancelled) log.info(s"No workload for cancelled flight ${f.apiFlight.IATA}")
      cancelled
    }

    def crunchLoads(loadMinutes: mutable.Map[TQM, LoadMinute], firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToCrunch: Set[TerminalName]): SortedMap[TQM, DeskRecMinute] = {
      val terminalQueueDeskRecs = for {
        terminal <- terminalsToCrunch
        queue <- airportConfig.nonTransferQueues(terminal)
      } yield {
        val lms = (firstMinute until lastMinute by 60000).map(minute =>
          loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)))
        crunchQueue(firstMinute, lastMinute, terminal, queue, lms)
      }
      SortedMap[TQM, DeskRecMinute]() ++ terminalQueueDeskRecs.toSeq.flatten
    }

    def crunchQueue(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, tn: TerminalName, qn: QueueName, qLms: IndexedSeq[LoadMinute]): SortedMap[TQM, DeskRecMinute] = {
      val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
      val paxMinutes = qLms.map(_.paxLoad)
      val workMinutes = qLms.map(_.workLoad)
      val adjustedWorkMinutes = if (qn == Queues.EGate) workMinutes.map(_ / airportConfig.eGateBankSize) else workMinutes
      val minuteMillis: Seq[MillisSinceEpoch] = firstMinute until lastMinute by 60000
      val (minDesks, maxDesks) = minMaxDesksForQueue(minuteMillis, tn, qn)
      val start = SDate.now()
      val triedResult: Try[OptimizerCrunchResult] = crunch(adjustedWorkMinutes, minDesks, maxDesks, OptimizerConfig(sla))
      triedResult match {
        case Success(OptimizerCrunchResult(desks, waits)) =>
          log.debug(s"Optimizer for $qn Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
          SortedMap[TQM, DeskRecMinute]() ++ minuteMillis.zipWithIndex.map {
            case (minute, idx) =>
              val wl = workMinutes(idx)
              val pl = paxMinutes(idx)
              val drm = DeskRecMinute(tn, qn, minute, pl, wl, desks(idx), waits(idx))
              (drm.key, drm)
          }
        case Failure(t) =>
          log.warn(s"failed to crunch: $t")
          SortedMap[TQM, DeskRecMinute]()
      }
    }

    def minMaxDesksForQueue(deskRecMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    val graph = GraphDSL.create(
      Source.actorRefWithAck[List[Long]](Ack).async,
      KillSwitches.single[DeskRecMinutes])((_, _)) {
      implicit builder =>
        (daysToCrunchAsync, killSwitch) =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysToCrunchAsync.out
            .map(_.map(min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch))
            .conflateWithSeed { initial =>
              val daysToCrunch = SortedSet[MillisSinceEpoch]() ++ initial
              log.info(s"initial queue: ${daysToCrunch.map(SDate(_).toISOString())}")
              daysToCrunch
            } {
              case (acc, incoming) =>
                val daysToCrunchQueue = acc ++ incoming
                log.info(s"queue now ${daysToCrunchQueue.map(SDate(_).toISOString())}")
                daysToCrunchQueue
            }
            .mapConcat(identity)
            .mapAsync(1) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString()}")
              askablePortStateActor
                .ask(GetFlights(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
                .asInstanceOf[Future[FlightsWithSplits]]
                .map(fs => (crunchStartMillis, fs))
            }
            .map { case (crunchStartMillis, flights) =>
              log.info(s"Crunching ${SDate(crunchStartMillis).toISOString()}")
              val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
              val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
              crunchLoads(flightLoadMinutes(flights).minutes, crunchStartMillis, crunchEndMillis, terminals)
            }
            .map(drms => DeskRecMinutes(drms.values.toSeq)) ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

}

case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch)
