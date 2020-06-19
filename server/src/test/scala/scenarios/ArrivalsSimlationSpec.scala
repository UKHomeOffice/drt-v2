package scenarios

import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.Exports
import services.exports.summaries.flights.TerminalFlightsWithActualApiSummary

class ArrivalsSimlationSpec extends CrunchTestLike {


  val csv: String =
    """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates
       |TST100,TST100,SIN,/535,Landed,2020-06-17,05:50,05:38,05:38,05:45,05:45,05:53,30,14,,,,,9,4,1,,7,2,5,0,0.0,0.0,8.0,7.0,1.0,1.0,13.0,46.0"""
      .stripMargin

  "Given a CSV with all the columns we need in it then we should get a flight with splits" >> {

    val result1: Array[ApiFlightWithSplits] = ArrivalImporter(csv, Terminal("T5"))

    val csv2 = TerminalFlightsWithActualApiSummary(
      result1,
      Exports.millisToUtcIsoDateOnly,
      Exports.millisToUtcHoursAndMinutes,
      PcpPax.bestPaxEstimateWithApi
    ).toCsvWithHeader

    val result2 = ArrivalImporter(csv2, Terminal("T5"))

    result1.head === result2.head
  }

  "Given an APIFlightWithSplits then I should be able to convert it into a CSV and back to the same APIFlightWithSplits" >> {
    val flight: Arrival = ArrivalGenerator.arrival(
      iata = "TST100",
      actPax = Option(200),
      tranPax = Option(0),
      schDt = "2020-06-17T05:30:00Z",
      terminal = Terminal("T5"),
      airportId = PortCode("ID"),
      status = ArrivalStatus("Scheduled"),
      feedSources = Set(LiveFeedSource),
      pcpDt = "2020-06-17T06:30:00Z"
    )
    val splits = Splits(Set(
      ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 1.0, None),
      ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 2.0, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 3.0, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 4.0, None),
      ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 5.0, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 6.0, None),
      ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 7.0, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 8.0, None)
    ), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val expected: ApiFlightWithSplits = ApiFlightWithSplits(flight, Set(splits))

    val csvFromFlightWithSplits = TerminalFlightsWithActualApiSummary(
      Seq(expected),
      Exports.millisToUtcIsoDateOnly,
      Exports.millisToUtcHoursAndMinutes,
      PcpPax.bestPaxEstimateWithApi
    ).toCsvWithHeader

    val result: ApiFlightWithSplits = ArrivalImporter(csvFromFlightWithSplits, Terminal("T5")).head

    result === expected

  }

  "Given an arrival CSV row then I should get back a representative ApiPaxTypeAndQueueCount split for the arrival" >> {

    val csvLines = ArrivalImporter.toLines(csv)
    val headers = ArrivalImporter.csvHeadings(csvLines)

    val result = ArrivalImporter.lineToSplits(ArrivalImporter.lineToFields(csvLines(1)), headers)

    val expected: Set[Splits] = Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 0, None),
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 0, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 8.0, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 7.0, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 1.0, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 1.0, None),
        ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 13.0, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 46.0, None)
      ),
      ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)
    ))

    result === expected

  }


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


  def offerArrivalAndWait(input: SourceQueueWithComplete[ArrivalsFeedResponse],
                          scheduled: String,
                          actPax: Option[Int],
                          tranPax: Option[Int],
                          maxPax: Option[Int],
                          status: String = "",
                          actChoxDt: String = ""): QueueOfferResult = {
    val arrivalLive = ArrivalGenerator.arrival("BA0001", schDt = scheduled, actPax = actPax, tranPax = tranPax, maxPax = maxPax, status = ArrivalStatus(status), actChoxDt = actChoxDt)
    offerAndWait(input, ArrivalsFeedSuccess(Flights(Seq(arrivalLive))))
  }
}
