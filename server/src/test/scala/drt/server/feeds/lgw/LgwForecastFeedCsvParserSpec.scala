package drt.server.feeds.lgw

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, ForecastArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.S
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

class LgwForecastFeedCsvParserSpec extends Specification {
  val pax = 155
  val carrierCode = "XX"
  val flightNumber = 101
  val origin = "TNG"

  "Given a csv string containing flights scheduled in the Winter" >> {
    val scheduled = SDate("2023-01-01T12:00:00", europeLondonTimeZone)
    val content: String =
      s"""Date,Flight Number,POA Forecast Version,Ope,Seats,Aircraft Type,Terminal,ArrDep,Orig Dest,Airport Code,Scheduled Time,POA PAX,Transfer PAX,CSA PAX,Time,Hour,Int/Dom,Date/Time
         |01-Jan-23,${carrierCode}0${flightNumber},POA FCST 23-03-23,3O,174,320,South,Arrival,"Tangier (Ibn Batuta), Morocco",$origin,1200,$pax,0,$pax,12:00,11,I,01/01/2023 12:00
         |""".stripMargin

    "I should be able to parse it into a list of ForecastArrivals" >> {
      val parser = LgwForecastFeedCsvParser(() => Option(content))
      parser.parseCsv(content) === (List(
        ForecastArrival(CarrierCode(carrierCode), VoyageNumber(flightNumber), None, PortCode(origin), S, scheduled.millisSinceEpoch, Some(pax), None, Some(174)),
      ), 0)
    }
  }

  "Given a csv string containing flights scheduled in the Summer" >> {
    val scheduled = SDate("2023-06-01T12:00:00", europeLondonTimeZone)
    val content: String =
      s"""Date,Flight Number,POA Forecast Version,Ope,Seats,Aircraft Type,Terminal,ArrDep,Orig Dest,Airport Code,Scheduled Time,POA PAX,Transfer PAX,CSA PAX,Time,Hour,Int/Dom,Date/Time
         |01-Apr-23,${carrierCode}0${flightNumber},POA FCST 23-03-23,3O,174,320,South,Arrival,"Tangier (Ibn Batuta), Morocco",$origin,1200,$pax,0,$pax,12:00,11,I,01/06/2023 12:00
         |""".stripMargin

    "I should be able to parse it into a list of ForecastArrivals" >> {
      val parser = LgwForecastFeedCsvParser(() => Option(content))
      parser.parseCsv(content) === (List(
        ForecastArrival(CarrierCode(carrierCode), VoyageNumber(flightNumber), None, PortCode(origin), S, scheduled.millisSinceEpoch, Some(pax), None, Some(174)),
      ), 0)
    }
  }
}
