package services.imports

import actors.GetState
import actors.acking.AckingReceiver.{Ack, StreamInitialized}
import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared._
import services.SDate
import services.crunch.deskrecs.GetFlightsForDateRange

import scala.concurrent.{ExecutionContextExecutor, Promise}
import scala.util.Try

object ArrivalImporter {

  def apply(csv: String, terminal: Terminal): Array[ApiFlightWithSplits] = {

    val lines: Array[String] = toLines(csv)
    val headings = csvHeadings(lines)

    getArrivals(lines.tail, headings, terminal)
  }

  def csvHeadings(lines: Array[String]): Map[String, Int] = {
    lines.head.split(",").zipWithIndex.toMap
  }

  def toLines(csv: String): Array[String] = csv.split("\n")

  def getArrivals(lines: Array[String], h: Map[String, Int], terminal: Terminal): Array[ApiFlightWithSplits] =
    lines.map(lineToFields).map((arrivalFields: Array[String]) => {
      val arrival = lineToArrival(arrivalFields, h, terminal)
      val splits = lineToSplits(arrivalFields, h)
      ApiFlightWithSplits(arrival, splits)
    })

  def lineToFields(line: String): Array[String] = line.split(",")

  def lineToArrival(arrivalFields: Array[String], h: Map[String, Int], terminal: Terminal): Arrival = {

    val dateString = arrivalFields(h("Scheduled Date"))
    val millisForTimeFn: String => MillisSinceEpoch = dateTimeStringToMillis(dateString)

    val maybeTotalPax = csvOptionalField(arrivalFields(h("Total Pax"))).map(_.toInt)
    val maybePcpPax = csvOptionalField(arrivalFields(h("PCP Pax"))).map(_.toInt)
    val maybeTransPax = for {
      total <- maybeTotalPax
      pcp <- maybePcpPax
    } yield total - pcp

    val maybeGateStand = csvOptionalField(arrivalFields(h("Gate/Stand")))
    val maybeGate: Option[String] = maybeGateStand.flatMap(_.split("/").headOption)
    val maybeStand = maybeGateStand.flatMap(_.split("/").reverse.headOption)

    Arrival(
      Operator = None,
      Status = ArrivalStatus(csvOptionalField(arrivalFields(h("Status"))).getOrElse("")),
      Estimated = csvOptionalField(arrivalFields(h("Est Arrival"))).map(millisForTimeFn),
      Actual = csvOptionalField(arrivalFields(h("Act Arrival"))).map(millisForTimeFn),
      EstimatedChox = csvOptionalField(arrivalFields(h("Est Chox"))).map(millisForTimeFn),
      ActualChox = csvOptionalField(arrivalFields(h("Act Chox"))).map(millisForTimeFn),
      Gate = maybeGate,
      Stand = maybeStand,
      MaxPax = None,
      ActPax = maybeTotalPax,
      TranPax = maybeTransPax,
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = PortCode("ID"),
      Terminal = terminal,
      rawICAO = arrivalFields(h("ICAO")),
      rawIATA = arrivalFields(h("IATA")),
      Origin = PortCode(arrivalFields(h("Origin"))),
      Scheduled = millisForTimeFn(arrivalFields(h("Scheduled Time"))),
      PcpTime = csvOptionalField(arrivalFields(h("Est PCP"))).map(millisForTimeFn),
      FeedSources = Set(LiveFeedSource),
      CarrierScheduled = None,
      ApiPax = None
    )
  }

  def lineToSplits(arrivalFields: Array[String], h: Map[String, Int]): Set[Splits] = {
    val splitsSet = Set(
      (B5JPlusNational, Queues.EeaDesk, "API Actual - B5JSSK to Desk"),
      (B5JPlusNational, Queues.EGate, "API Actual - B5JSSK to eGates"),
      (EeaMachineReadable, Queues.EeaDesk, "API Actual - EEA (Machine Readable)"),
      (EeaNonMachineReadable, Queues.EeaDesk, "API Actual - EEA (Non Machine Readable)"),
      (NonVisaNational, Queues.NonEeaDesk, "API Actual - Non EEA (Non Visa)"),
      (VisaNational, Queues.NonEeaDesk, "API Actual - Non EEA (Visa)"),
      (Transit, Queues.Transfer, "API Actual - Transfer"),
      (EeaMachineReadable, Queues.EGate, "API Actual - eGates")
    ).collect {
      case (paxType, queue, key) if h.contains(key) =>
        ApiPaxTypeAndQueueCount(paxType, queue, arrivalFields(h(key)).toDouble, None)
    }
    Set(Splits(splitsSet, ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)))
  }

  def csvOptionalField(field: String): Option[String] = field match {
    case s if s.isEmpty => None
    case s => Option(s)
  }

  def dateTimeStringToMillis(date: String)(time: String): MillisSinceEpoch =
    SDate(date + "T" + time).millisSinceEpoch
}

class ArrivalCrunchSimulationActor(fws: FlightsWithSplits) extends Actor with ActorLogging {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher

  var promisedResult: Promise[DeskRecMinutes] = Promise[DeskRecMinutes]

  override def receive: Receive = {
    case GetFlightsForDateRange(_, _) =>
      sender() ! fws

    case GetState =>
      val replyTo = sender()
      promisedResult.future.pipeTo(replyTo)

    case m: DeskRecMinutes =>
      promisedResult.complete(Try(m))

    case StreamInitialized =>
      sender() ! Ack

    case unexpected =>
      log.warning(s"Got and unexpected message $unexpected")
  }
}
