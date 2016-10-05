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

trait WorkloadsService extends WorkloadsApi {
  self: FlightsService =>
  private val log = LoggerFactory.getLogger(getClass)

  def numberOf15Mins = (24 * 4 * 15)

  def maxLoadPerSlot: Int = 20

  def splitRatioProvider(flight: ApiFlight) = List(
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.2),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.8)
  )

  def procTimesProvider(paxTypeAndQueue: PaxTypeAndQueue): Double = paxTypeAndQueue match {
    case _ => 1.0
    //    case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) => 0.25
    //    case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) => 0.20
    //    case PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) => 1.0
    //    case PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) => 0.4
    //    case PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) => 1.0
  }

  override def getWorkloads(): Future[Map[TerminalName, Map[QueueName, QueueWorkloads]]] = {
    // val what: Future[Seq[(TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])])]] = for {
    //   allFlights <- getFlights(0, 0)
    //   flightsByTerminal = allFlights.groupBy(_.Terminal)
    //   fbt <- flightsByTerminal.iterator 
    //   terminal = fbt._1
    //   flights = fbt._2
    // } yield {
    //   val res: Map[QueueName, (Seq[WL], Seq[Pax])] = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider, procTimesProvider)(flights)
    //   log.info(s"workloads ${res}")
    //   (terminal -> res)
    // }
    // what.map(_.toMap)

    val whatByMap: Future[Map[String, List[ApiFlight]]] = getFlights(0, 0).map(fs => {
      val flightsByTerminal = fs.filterNot(_.Terminal == "FRT").groupBy(_.Terminal)
      flightsByTerminal
    })
    val plc = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider, procTimesProvider)_

    val whatByTerminal: Future[Map[String, Map[QueueName, (Seq[WL], Seq[Pax])]]] = whatByMap.map(flightsByTerminal => flightsByTerminal.map(fbt => {
      val terminal = fbt._1
      val flights = fbt._2
      (terminal -> plc(flights))
    }))

    val terminals = whatByTerminal.onSuccess{
      case s =>
        log.info(s"========should return ${s}")
        val terminals =s.keys
        log.info(s"getWorkloads terminals a ${terminals}")
    }

    whatByTerminal
  }
}
