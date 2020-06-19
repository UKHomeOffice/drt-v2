package services.imports

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PaxTypes.{B5JPlusNational, EeaMachineReadable, EeaNonMachineReadable, NonVisaNational, Transit, VisaNational}
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.{ApiFlightWithSplits, ApiPaxTypeAndQueueCount, ArrivalStatus, EventTypes, LiveFeedSource, PortCode, Queues, Splits}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import services.SDate


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

  def lineToSplits(arrivalFields: Array[String], h: Map[String, Int]): Set[Splits] = Set(
    Splits(
      Set(
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, arrivalFields(h("API Actual - B5JSSK to Desk")).toDouble, None),
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, arrivalFields(h("API Actual - B5JSSK to eGates")).toDouble, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, arrivalFields(h("API Actual - EEA (Machine Readable)")).toDouble, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, arrivalFields(h("API Actual - EEA (Non Machine Readable)")).toDouble, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, arrivalFields(h("API Actual - Non EEA (Non Visa)")).toDouble, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, arrivalFields(h("API Actual - Non EEA (Visa)")).toDouble, None),
        ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, arrivalFields(h("API Actual - Transfer")).toDouble, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, arrivalFields(h("API Actual - eGates")).toDouble, None)
      ),
      ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)
    )
  )

  def csvOptionalField(field: String): Option[String] = field match {
    case s if s.isEmpty => None
    case s => Option(s)
  }

  def dateTimeStringToMillis(date: String)(time: String): MillisSinceEpoch =
    SDate(date + "T" + time).millisSinceEpoch
}
