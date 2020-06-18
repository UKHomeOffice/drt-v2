package scenarios

import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.Exports
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsWithActualApiSummary}

class ArrivalsSimlationSpec extends CrunchTestLike {

  val csv =
    """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates
       |BA0012,BA0012,SIN,/535,Landed,2020-06-17,05:50,05:38,05:38,05:45,05:45,05:53,30,14,,,,,9,4,1,,7,2,5,0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0"""
      .stripMargin

  "Given a CSV with all the columns we need in it then we should get a flight with splits" >> {

    val result1: Array[Arrival] = ArrivalImporter(csv, Terminal("T5"))

    val fws = result1.map(f => ApiFlightWithSplits(f, Set()))
    val csv2 = TerminalFlightsWithActualApiSummary(
      fws,
      Exports.millisToUtcIsoDateOnly,
      Exports.millisToUtcHoursAndMinutes,
      PcpPax.bestPaxEstimateWithApi
    ).toCsvWithHeader

    val result2 = ArrivalImporter(csv2, Terminal("T5"))

    result1.head === result2.head
  }

  object ArrivalImporter {

    def apply(csv: String, terminal: Terminal) = {

      val lines: Array[String] = csv.split("\n")
      val headings = lines.head.split(",").zipWithIndex.toMap

      getArrivals(lines, headings, terminal)

    }

    def getArrivals(lines: Array[String], h: Map[String, Int], terminal: Terminal) =
      lines.tail.map(_.split(",")).map((arrivalFields: Array[String]) => {
        lineToArrival(arrivalFields, h, terminal)
      })

    def lineToArrival(arrivalFields: Array[String], h: Map[String, Int], terminal: Terminal) = {

      val dateString = arrivalFields(h("Scheduled Date"))
      val millisForTime = dateTimeStringToMillis(dateString) _
      val maybeTotalPax = csvOptionalField(arrivalFields(h("Total Pax"))).map(_.toInt)
      val maybePcpPax = csvOptionalField(arrivalFields(h("PCP Pax"))).map(_.toInt)
      val maybeTransPax = for {
        total <- maybeTotalPax
        pcp <- maybePcpPax
      } yield total - pcp
      Arrival(
        Operator = None,
        Status = ArrivalStatus(csvOptionalField(arrivalFields(h("Status"))).getOrElse("Forecast")),
        Estimated = csvOptionalField(arrivalFields(h("Est Arrival"))).map(millisForTime),
        Actual = csvOptionalField(arrivalFields(h("Act Arrival"))).map(millisForTime),
        EstimatedChox = csvOptionalField(arrivalFields(h("Est Chox"))).map(millisForTime),
        ActualChox = csvOptionalField(arrivalFields(h("Act Chox"))).map(millisForTime),
        Gate = csvOptionalField(arrivalFields(h("Gate/Stand"))).map(_.split("/").head),
        Stand = csvOptionalField(arrivalFields(h("Gate/Stand"))).map(_.split("/")(1)),
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
        Scheduled = millisForTime(arrivalFields(h("Scheduled Time"))),
        PcpTime = csvOptionalField(arrivalFields(h("Est PCP"))).map(millisForTime),
        FeedSources = Set(LiveFeedSource),
        CarrierScheduled = None,
        ApiPax = None
      )
    }

    private def csvOptionalField(field: String) = field match {
        case s if s.isEmpty => None
        case s => Option(s)
      }

    def dateTimeStringToMillis(date: String)(time: String) = {

      println(date + "T" + time)
      val sdate = SDate(date + "T" + time)
      println(sdate.toISOString())
      sdate.millisSinceEpoch
    }
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
