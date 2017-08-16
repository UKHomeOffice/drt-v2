package services

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.util.Try

object Crunch {

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

  case class CrunchState(
                          flights: List[ApiFlightWithSplits],
                          workloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, Load)]]],
                          crunchResult: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]],
                          crunchFirstMinuteMillis: MillisSinceEpoch
                        )

  case class Props(subscriber: ActorRef,
                   slas: Map[QueueName, Int],
                   minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                   procTimes: Map[PaxTypeAndQueue, Double], startTime: Long, endTime: Long)

  implicit val system = ActorSystem("reactive-crunch")
  implicit val materializer = ActorMaterializer()

  case class Publisher(subscriber: ActorRef, props: Props) {
    val crunchFlow = new CrunchStateFlow(props.slas, props.minMaxDesks, props.procTimes, props.startTime, props.endTime)

    def publish(flights: List[ApiFlightWithSplits]) =
      Source(List(flights))
        .via(crunchFlow)
        .to(Sink.actorRef(subscriber, "completed"))
        .run()
  }

  class CrunchStateFlow(slas: Map[QueueName, Int],
                        minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                        procTimes: Map[PaxTypeAndQueue, Double], startTime: Long, endTime: Long)
    extends GraphStage[FlowShape[List[ApiFlightWithSplits], CrunchState]] {

    val in = Inlet[List[ApiFlightWithSplits]]("DigestCalculator.in")
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
          val flightsWithSplits = grab(in)
          val qlm: Set[QueueLoadMinute] = flightsToQueueLoadMinutes(procTimes)(flightsWithSplits)
          val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, ProcTime]]] = indexQueueWorkloadsByMinute(qlm)
          val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, Load)]]] = queueMinutesForPeriod(startTime, endTime)(wlByQueue)
          val crunchResults: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = queueWorkloadsToCrunchResults(fullWlByQueue, slas, minMaxDesks)
          val crunchState = CrunchState(flightsWithSplits, fullWlByQueue, crunchResults, startTime)
          crunchStateOption = Option(crunchState)

          if (isAvailable(out)) {
            outAwaiting = false
            push(out, crunchState)
          }
        }
      })
    }
  }

  def queueWorkloadsToCrunchResults(portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, Double)]]],
                                    slas: Map[QueueName, Int],
                                    minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]]
                                   ): Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = {
    portWorkloads.map {
      case (terminalName, terminalWorkloads) =>
        val terminalCrunchResults = terminalWorkloads.map {
          case (queueName, queueWorkloads) =>
            val workloadMinutes = queueWorkloads.map(_._2)
            val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
            val sla = slas.getOrElse(queueName, 0)
            val firstMinuteOfCrunch = SDate(queueWorkloads.head._1).getHours * 60 + SDate(queueWorkloads.head._1).getMinutes
            val queueMinMaxDesks = minMaxDesks.getOrElse(terminalName, Map()).getOrElse(queueName, defaultMinMaxDesks)
            val minDesks = inflateAndSlice(queueMinMaxDesks._1, firstMinuteOfCrunch, workloadMinutes.length)
            val maxDesks = inflateAndSlice(queueMinMaxDesks._2, firstMinuteOfCrunch, workloadMinutes.length)
            println(s"crunching $terminalName - $queueName")
            println(s"wl: ${workloadMinutes.length}, minDesk: ${minDesks.length}")
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))
            (queueName, triedResult)
        }
        (terminalName, terminalCrunchResults)
    }
  }

  def inflateAndSlice(desks: Seq[Int], first: Int, length: Int): Seq[Int] =
    desks.flatMap(List.fill(60)(_)).slice(first, first + length)

  def queueMinutesForPeriod(startTime: Long, endTime: Long)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, Double]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, Load)]]] =
    terminal.mapValues(queue => queue.mapValues(queueWorkloadMinutes =>
      List.range(startTime, endTime + 60000, 60000).map(minute => {
        (minute, queueWorkloadMinutes.getOrElse(minute, 0d))
      })
    ))

  def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, Double]]] = {
    val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

    portLoads.mapValues(terminalLoads => {
      val queueLoads = terminalLoads.groupBy(_.queueName)
      queueLoads
        .mapValues(_.map(qwl =>
          qwl.minute -> qwl.workLoad
        ).toMap)
    })
  }

  def flightsToQueueLoadMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Set[QueueLoadMinute] =
    flightsWithSplits.flatMap {
      case ApiFlightWithSplits(flight, splits) =>
        val flightSplitMinutes: immutable.Seq[FlightSplitMinute] = flightToFlightSplitMinutes(flight, splits, procTimes)
        val queueLoadMinutes: immutable.Iterable[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flight.Terminal, flightSplitMinutes)

        queueLoadMinutes
    }.toSet

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
