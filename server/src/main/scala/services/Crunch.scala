package services

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.util.Try

object Crunch {

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

  case class CrunchState(
                          flights: List[ApiFlightWithSplits],
                          workloads: Map[TerminalName, Map[QueueName, List[(Long, (Double, Double))]]],
                          crunchResult: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]],
                          crunchFirstMinuteMillis: MillisSinceEpoch
                        )


  case class CrunchFlights(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch, crunchEnd: MillisSinceEpoch)

  trait PublisherLike {
    def publish(crunchFlights: CrunchFlights): NotUsed
  }

  case class Publisher(subscriber: ActorRef, crunchFlow: CrunchStateFlow)(implicit val mat: ActorMaterializer) extends PublisherLike {

    def publish(crunchFlights: CrunchFlights) =
      Source(List(crunchFlights))
        .via(crunchFlow)
        .to(Sink.actorRef(subscriber, "completed"))
        .run()
  }

  class CrunchStateFlow(slas: Map[QueueName, Int],
                        minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                        procTimes: Map[PaxTypeAndQueue, Double],
                        groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                        validPortTerminals: Set[String])
    extends GraphStage[FlowShape[CrunchFlights, CrunchState]] {

    val in = Inlet[CrunchFlights]("DigestCalculator.in")
    val out = Outlet[CrunchState]("DigestCalculator.out")
    override val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      var crunchStateOption: Option[CrunchState] = None
      var outAwaiting = false

      setHandlers(in, out, new InHandler with OutHandler {
        override def onPull(): Unit = {
          crunchStateOption match {
            case Some(crunchState) =>
              outAwaiting = false
              push(out, crunchState)
            case None =>
              outAwaiting = true
              if (!hasBeenPulled(in)) pull(in)
          }
        }

        override def onPush(): Unit = {
          val crunchFlights: CrunchFlights = grab(in)
          val flightsToValidTerminals = crunchFlights.flights.filter {
            case ApiFlightWithSplits(flight, _) => validPortTerminals.contains(flight.Terminal)
          }
          val uniqueFlights = groupFlightsByCodeShares(flightsToValidTerminals).map(_._1)
          val qlm: Set[QueueLoadMinute] = flightsToQueueLoadMinutes(procTimes)(uniqueFlights)
          println(s"qlm: $qlm")
          val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = indexQueueWorkloadsByMinute(qlm)
          println(s"wlByQueue: $wlByQueue")

          val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = queueMinutesForPeriod(crunchFlights.crunchStart, crunchFlights.crunchEnd)(wlByQueue)
          val eGateBankSize = 5
          val crunchResults: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = queueWorkloadsToCrunchResults(crunchFlights.crunchStart, fullWlByQueue, slas, minMaxDesks, eGateBankSize)
          val crunchState = CrunchState(crunchFlights.flights, fullWlByQueue, crunchResults, crunchFlights.crunchStart)
          crunchStateOption = Option(crunchState)

          if (isAvailable(out)) {
            outAwaiting = false
            push(out, crunchState)
          }
        }
      })
    }
  }

  val oneMinute = 60000

  def queueWorkloadsToCrunchResults(crunchStartMillis: MillisSinceEpoch,
                                    portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]],
                                    slas: Map[QueueName, Int],
                                    minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                                    eGateBankSize: Int): Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = {
    portWorkloads.map {
      case (terminalName, terminalWorkloads) =>
        val terminalCrunchResults = terminalWorkloads.map {
          case (queueName, queueWorkloads) =>
            val workloadMinutes = queueName match {
              case Queues.EGate => queueWorkloads.map(_._2._2 / eGateBankSize)
              case _ => queueWorkloads.map(_._2._2)
            }
            val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
            val sla = slas.getOrElse(queueName, 0)
            val queueMinMaxDesks = minMaxDesks.getOrElse(terminalName, Map()).getOrElse(queueName, defaultMinMaxDesks)
            val crunchEndTime = crunchStartMillis + ((workloadMinutes.length * oneMinute) - oneMinute)
            val crunchMinutes = crunchStartMillis to crunchEndTime by oneMinute
            val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
            val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
            println(s"crunching $terminalName - $queueName")
            println(s"wl: ${workloadMinutes.length}, minDesk: ${minDesks.length}")
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))
            (queueName, triedResult)
        }
        (terminalName, terminalCrunchResults)
    }
  }

  def desksForHourOfDayInUKLocalTime(startTimeMidnightBST: MillisSinceEpoch, desks: Seq[Int]) = {
    val date = new DateTime(startTimeMidnightBST).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def inflateAndSlice(desks: Seq[Int], first: Int, length: Int): Seq[Int] =
    desks.flatMap(List.fill(60)(_)).slice(first, first + length)

  def queueMinutesForPeriod(startTime: Long, endTime: Long)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] =
    terminal.mapValues(queue => queue.mapValues(queueWorkloadMinutes =>
      List.range(startTime, endTime + oneMinute, oneMinute).map(minute => {
        (minute, queueWorkloadMinutes.getOrElse(minute, (0d, 0d)))
      })
    ))

  def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = {
    val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

    portLoads.mapValues(terminalLoads => {
      val queueLoads = terminalLoads.groupBy(_.queueName)
      queueLoads
        .mapValues(_.map(qwl =>
          qwl.minute -> (qwl.paxLoad, qwl.workLoad)
        ).toMap)
    })
  }

  def flightsToQueueLoadMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Set[QueueLoadMinute] = {
    val queueLoadMinutes = flightsWithSplits.flatMap {
      case ApiFlightWithSplits(flight, splits) =>
        val flightSplitMinutes: Seq[FlightSplitMinute] = flightToFlightSplitMinutes(flight, splits, procTimes)
        val queueLoadMinutes: immutable.Iterable[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flight.Terminal, flightSplitMinutes)
        queueLoadMinutes
    }
    collapseQueueLoadMinutesToSet(queueLoadMinutes)
  }

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[QueueLoadMinute]) = {
    queueLoadMinutes
      .groupBy(qlm => (qlm.terminalName, qlm.queueName, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          QueueLoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def flightToFlightSplitMinutes(flight: Arrival,
                                 splits: List[ApiSplits],
                                 procTimes: Map[PaxTypeAndQueue, Double]): immutable.IndexedSeq[FlightSplitMinute] = {
    val splitsToUse = splits.head
    val totalPax = splitsToUse.splits.map(qc => qc.paxCount).sum
    val splitRatios = splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / totalPax))

    minutesForHours(flight.PcpTime, 1)
      .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
      .flatMap {
        case (minuteMillis, flightPaxInMinute) =>
          splitRatios.map(apiSplitRatio => flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplitRatio))
      }
  }

  def flightSplitMinute(flight: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int, apiSplitRatio: ApiPaxTypeAndQueueCount): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val splitWorkLoadInMinute = splitPaxInMinute * procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
    FlightSplitMinute(flight.FlightID, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
  }

  def flightSplitMinutesToQueueLoadMinutes(terminalName: TerminalName, flightSplitMinutes: Seq[FlightSplitMinute]) = {
    flightSplitMinutes
      .groupBy(s => (s.queueName, s.minute)).map {
      case ((queueName, minute), fsms) =>
        val paxLoad = fsms.map(_.paxLoad).sum
        val workLoad = fsms.map(_.workLoad).sum
        QueueLoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
    }
  }
}
