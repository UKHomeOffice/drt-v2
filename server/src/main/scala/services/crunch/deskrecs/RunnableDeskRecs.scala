package services.crunch.deskrecs

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.{Crunch, WorkloadCalculator}
import services.{SDate, TryCrunch}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(MilliDate(adjustedMinute.millisSinceEpoch)).addMinutes(offsetMinutes)
  }

  def apply(portStateActor: ActorRef,
            minutesToCrunch: Int,
            crunch: TryCrunch,
            airportConfig: AirportConfig,
            bestPaxFn: Arrival => Int
           )(implicit executionContext: ExecutionContext, timeout: Timeout = new Timeout(10 seconds)): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val askablePortStateActor: AskableActorRef = portStateActor

    val crunchPeriodStartMillis: SDateLike => SDateLike = crunchStartWithOffset(airportConfig.crunchOffsetMinutes)

    val graph = GraphDSL.create(
      Source.actorRefWithAck[List[Long]](Ack).async,
      KillSwitches.single[DeskRecMinutes])((_, _)) {
      implicit builder =>
        (daysToCrunchAsync, killSwitch) =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val parallelismLevel = 2

          daysToCrunchAsync.out
            .map(_.map(min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch).toSet.toList)
            .statefulMapConcat {
              processQueueOfDaysToCrunch(parallelismLevel)
            }
            .mapAsync(parallelismLevel) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString()}")
              flightsToCrunch(minutesToCrunch, askablePortStateActor, crunchStartMillis)
            }
            .map { case (crunchStartMillis, flights) =>
              log.info(s"Crunching ${SDate(crunchStartMillis).toISOString()} flights: ${flights.flightsToUpdate.size}")
              crunchFlights(flights, crunchStartMillis, minutesToCrunch, crunch, airportConfig, bestPaxFn)
            }
            .map(drms => DeskRecMinutes(drms.values.toSeq)) ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  private def crunchFlights(flights: FlightsWithSplits,
                            crunchStartMillis: MillisSinceEpoch,
                            minutesToCrunch: Int,
                            crunch: TryCrunch,
                            airportConfig: AirportConfig,
                            bestPaxFn: Arrival => Int
                           ): SortedMap[TQM, CrunchApi.DeskRecMinute] = {
    val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
    val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
    val loadMinutes = WorkloadCalculator(bestPaxFn).flightLoadMinutes(flights, airportConfig.terminalProcessingTimes).minutes

    val loadsWithDiverts = loadMinutes
      .groupBy {
        case (TQM(t, q, m), _) => val finalQueueName = airportConfig.divertedQueues.getOrElse(q, q)
          TQM(t, finalQueueName, m)
      }
      .map {
        case (tqm, mins) =>
          val loads = mins.values
          (tqm, LoadMinute(tqm.terminalName, tqm.queueName, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
      }

    crunchLoads(loadsWithDiverts, crunchStartMillis, crunchEndMillis, terminals, airportConfig, crunch)
  }

  private def processQueueOfDaysToCrunch(parallelismLevel: Int): () => List[MillisSinceEpoch] => List[MillisSinceEpoch] = {
    () =>
      var queue = SortedSet[MillisSinceEpoch]()
      incoming => {
        queue = queue ++ incoming
        val nextToProcess = queue match {
          case q if q.nonEmpty =>
            val nextToProcess = q.take(parallelismLevel)
            queue = queue.drop(parallelismLevel)
            List(nextToProcess).flatten
          case _ =>
            List()
        }
        log.info(s"Incoming day to crunch ${incoming.map(SDate(_).toISOString())}. Queue now: ${queue.map(SDate(_).toISOString())}")

        nextToProcess
      }
  }

  private def flightsToCrunch(minutesToCrunch: Int, askablePortStateActor: AskableActorRef, crunchStartMillis: MillisSinceEpoch)
                             (implicit executionContext: ExecutionContext, timeout: Timeout): Future[(MillisSinceEpoch, FlightsWithSplits)] = askablePortStateActor
    .ask(GetFlights(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
    .asInstanceOf[Future[FlightsWithSplits]]
    .map { fs => (crunchStartMillis, fs) }
    .recoverWith {
      case t =>
        log.error("Failed to fetch flights from PortStateActor", t)
        Future((crunchStartMillis, FlightsWithSplits(List(), List())))
    }
}

case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch)
