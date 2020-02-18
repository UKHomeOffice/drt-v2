package services.crunch.deskrecs

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.{Buffer, Crunch}

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(portStateActor: ActorRef,
            portDeskRecs: DesksAndWaitsPortProviderLike,
            buffer: Buffer,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
           (implicit executionContext: ExecutionContext,
            timeout: Timeout = new Timeout(10 seconds)): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val askablePortStateActor: AskableActorRef = portStateActor

    val crunchPeriodStartMillis: SDateLike => SDateLike = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes)

    val graph = GraphDSL.create(
      Source.actorRefWithAck[List[Long]](Ack).async.addAttributes(Attributes.inputBuffer(1, 1000)),
      KillSwitches.single[DeskRecMinutes])((_, _)) {
      implicit builder =>
        (daysToCrunchAsync, killSwitch) =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val bufferAsync = builder.add(buffer.async)

          daysToCrunchAsync.out
            .map(_.map { min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch }.distinct) ~> bufferAsync

          bufferAsync
            .mapAsync(1) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString}")
              flightsToCrunch(askablePortStateActor)(portDeskRecs.minutesToCrunch, crunchStartMillis)
            }
            .map { case (crunchStartMillis, flights) =>
              val crunchEndMillis = SDate(crunchStartMillis).addMinutes(portDeskRecs.minutesToCrunch).millisSinceEpoch
              val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

              log.info(s"Crunching ${minuteMillis.length} minutes (${SDate(crunchStartMillis).toISOString} to ${SDate(crunchEndMillis).toISOString})")

              val loads = portDeskRecs.flightsToLoads(flights, crunchStartMillis)
              val maxDesksByTerminal = deskLimits(maxDesksProviders, flights.terminals, loads)

              portDeskRecs.loadsToDesks(minuteMillis, loads, maxDesksByTerminal)
            } ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph).addAttributes(Attributes.inputBuffer(1, 1))
  }

  def deskLimits(maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                 terminals: Set[Terminal],
                 loads: Map[TQM, Crunch.LoadMinute]): Map[Terminal, TerminalDeskLimitsLike] = {
    val validTerminals = loads.keys.map(_.terminal).toSet
    terminals.intersect(validTerminals).map { terminal =>
      (terminal, maxDesksProviders(terminal))
    }.toMap
  }

  private def flightsToCrunch(askablePortStateActor: AskableActorRef)
                             (minutesToCrunch: Int, crunchStartMillis: MillisSinceEpoch)
                             (implicit executionContext: ExecutionContext,
                              timeout: Timeout): Future[(MillisSinceEpoch, FlightsWithSplits)] = askablePortStateActor
    .ask(GetFlights(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
    .asInstanceOf[Future[FlightsWithSplits]]
    .map { fs => (crunchStartMillis, fs) }
    .recoverWith {
      case t =>
        log.error("Failed to fetch flights from PortStateActor", t)
        Future((crunchStartMillis, FlightsWithSplits(List(), List())))
    }

  def start(portStateActor: ActorRef,
            portDeskRecs: DesksAndWaitsPortProviderLike,
            now: () => SDateLike,
            recrunchOnStart: Boolean,
            forecastMaxDays: Int,
            maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike])
           (implicit ec: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val initialDaysToCrunch = if (recrunchOnStart) {
      val today = now()
      val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
      (0 until forecastMaxDays).map(d => {
        millisToCrunchStart(today.addDays(d)).millisSinceEpoch
      })
    } else Iterable()

    val buffer = Buffer(initialDaysToCrunch)

    RunnableDeskRecs(portStateActor, portDeskRecs, buffer, maxDesksProvider).run()
  }
}

case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch)
