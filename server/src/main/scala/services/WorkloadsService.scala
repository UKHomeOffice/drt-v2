package services

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.GetFlights
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import services.workloadcalculator.PassengerQueueTypes.{PaxType, PaxTypes, Queues}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.{PaxLoadCalculator, SplitRatio}
import spatutorial.shared.FlightsApi._
import spatutorial.shared._

import scala.collection.immutable.{Iterable, Seq}
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global

trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }
}

trait WorkloadsService extends WorkloadsApi with WorkloadsCalculator {
  self: FlightsService =>
  type WorkloadByTerminalQueue = Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]]

  override def getWorkloads(): Future[WorkloadByTerminalQueue] = getWorkloadsByTerminal(getFlights(0, 0))
}

trait DefaultPassengerSplitRatioProvider extends PassengerSplitRatioProvider {
  override def splitRatioProvider(flight: ApiFlight):  List[SplitRatio]  = List(
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.4875),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.05),
    SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.05)
  )

}

trait WorkloadsCalculator extends PassengerSplitRatioProvider {
  private val log = LoggerFactory.getLogger(getClass)

  type TerminalQueueWorkloads = Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]]

  def numberOf15Mins = (24 * 4 * 15)

  def maxLoadPerSlot: Int = 20


  def procTimesProvider(paxTypeAndQueue: PaxTypeAndQueue): Double = paxTypeAndQueue match {
    //    case _ => 1.0
    case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) => 20d / 60d
    case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) => 35d / 60d
    case PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) => 50d / 60d
    case PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) => 90d / 60d
    case PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) => 78d / 60d
  }


  def getWorkloadsByTerminal(flights: Future[List[ApiFlight]]): Future[TerminalQueueWorkloads] = {
    val flightsByTerminalFut: Future[Map[TerminalName, List[ApiFlight]]] = flights.map(fs => {
      val flightsByTerminal = fs.filterNot(freightOrEngineering).groupBy(_.Terminal)
      flightsByTerminal
    })
    val plc = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider, procTimesProvider) _

    val workloadByTerminal: Future[Map[String, Map[QueueName, (Seq[WL], Seq[Pax])]]] = flightsByTerminalFut.map(flightsByTerminal => flightsByTerminal.map(fbt => {
      log.info(s"Got flights by terminal ${fbt}")
      val terminal = fbt._1
      val flights = fbt._2
      (terminal -> plc(flights))
    }))

    workloadByTerminal
  }

  def freightOrEngineering(flight: ApiFlight): Boolean = Set("FRT", "ENG").contains(flight.Terminal)
}

