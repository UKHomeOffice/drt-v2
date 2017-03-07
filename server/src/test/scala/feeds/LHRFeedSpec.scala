package feeds

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import spatutorial.shared.ApiFlight

import sys.process._

class LHRFeedSpec extends Specification {
  val username = ConfigFactory.load.getString("lhr_live_username")
  val password = ConfigFactory.load.getString("lhr_live_password")

  "Executing the LHR feed script" should {
    "get us some content" in {
      val csvContents = Seq("/usr/local/bin/lhr-live-fetch-latest-feed.sh", "-u", username, "-p", password).!!

      csvContents.length > 0
    }
  }

  "lhrCsvToApiFlights" should {
    "Produce an ApiFlight based on a line from the LHR csv" in {
      //"Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
      val lhrCsvLine = "\"3\",\"RJ111\",\"Royal Jordanian\",\"AMM\",\"Amman\",\"14:45 06/03/2017\",\"14:45 06/03/2017\",\"14:45 06/03/2017\",\"14:50 06/03/2017\",\"15:05 06/03/2017\",\"303R\",\"162\",\"124\",\"33\""

      lhrCsvLine.substring(1, lhrCsvLine.length - 1).split("\",\"") match {
        case Array(terminal, flightNo, operator, from, origin, scheduled, estimated, touchdown, estChocks, actChocks, stand, maxPax, actPax, conPax) =>
          ApiFlight(
            Operator = operator,
            Status = "n/a",
            EstDT = estimated,
            ActDT = touchdown,
            EstChoxDT = estChocks,
            ActChoxDT = actChocks,
            Gate = "n/a",
            Stand = stand,
            MaxPax = maxPax.toInt,
            ActPax = actPax.toInt,
            TranPax = conPax.toInt,
            RunwayID = "n/a",
            BaggageReclaimId = "n/a",
            FlightID = 0,
            AirportID = "n/a",
            Terminal = s"T$terminal",
            ICAO = flightNo,
            IATA = flightNo,
            Origin = origin,
            SchDT = scheduled,
            PcpTime = 0
          )
      }

      //      def lhrCsvToApiFlights(csvLine: String): ApiFlight = {
      //        ApiFlight()
      //      }
      //      ApiFlight()
      //      Term	Flight No	Operator	From	Airport name	Scheduled	Estimated	Touchdown	Est Chocks	Act Chocks	Stand	Max pax	Act Pax	Conn pax
    }
  }
}
