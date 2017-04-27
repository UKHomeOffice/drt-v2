package services

import com.typesafe.config.ConfigFactory
import drt.shared.{ApiFlight, MilliDate}
import org.specs2.mutable.SpecificationLike

class WalkTimesSpec extends SpecificationLike {

  import WalkTimes._

  "walkTimesLinesFromConfig" >> {
    "Given a valid path to a walk times CSV file, " +
      "then we should get back the lines from that file in a Seq" >> {
      val walkTimesFileUrl = ConfigFactory.load.getString("walk_times.gates_csv_url")

      val result = walkTimesLinesFromFileUrl(walkTimesFileUrl).take(1)
      val expected = Seq("101,475,T1")

      result === expected
    }
  }

  "parseWalkTime" >> {
    "Given a valid walk time csv line string " +
      "then we should get back a WalkTime representing it" >> {
      val validCsvLine = "101,T1,475"

      val result = walkTimeFromString(validCsvLine)
      val expected = Some(WalkTime("101", "T1", 475000L))

      result === expected
    }

    "Given a invalid walk time csv line string with a non-numeric walk time, " +
      "then we should get a None" >> {
      val csvLineWithInvalidWalkTime = "101,T1,475A"

      val result = walkTimeFromString(csvLineWithInvalidWalkTime)
      val expected = None

      result === expected
    }
  }

  "walkTimeMillisProvider" >> {
    "Given no WalkTimes, " +
      "when we ask for the walk time millis for a WalkTime, " +
      "then we should get None" >> {
      val walkTimes = Seq()

      val result = walkTimeMillisProvider(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for that WalkTime, " +
      "then we should get the millis from it" >> {
      val walkTimes = Seq(WalkTime("1", "T1", 10000))

      val result = walkTimeMillisProvider(walkTimes)("1", "T1")
      val expected = Some(10000)

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for a different WalkTime, " +
      "then we should get None" >> {
      val walkTimes = Seq(WalkTime("1", "T1", 10000))

      val result = walkTimeMillisProvider(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given some WalkTimes, " +
      "when we ask for the walk time millis for one of them, " +
      "then we should get the millis for the matching WalkTime" >> {
      val walkTimes = Seq(
        WalkTime("1", "T1", 10000),
        WalkTime("2", "T1", 20000),
        WalkTime("3", "T1", 30000)
      )

      val result = walkTimeMillisProvider(walkTimes)("2", "T1")
      val expected = Some(20000)

      result === expected
    }
  }

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, defaultWalkTimeMillis: Long)
             (gateWalkTimesProvider: WalkTimeMillisProvider)
             (flight: ApiFlight): MilliDate = {
    val bestChoxTimeMillis: Long = bestChoxTime(timeToChoxMillis, flight)

    val walkTimeMillis = gateWalkTimesProvider(flight.Gate, flight.Terminal).getOrElse(defaultWalkTimeMillis)

    println(s"walkTimeMillis: $walkTimeMillis + bestChoxTimeMillis: $bestChoxTimeMillis")

    MilliDate(bestChoxTimeMillis + walkTimeMillis)
  }

  def bestChoxTime(timeToChoxMillis: Long, flight: ApiFlight) = {
    val bestChoxTimeMillis = if (flight.ActChoxDT != "") SDate.parseString(flight.ActChoxDT).millisSinceEpoch
    else if (flight.EstChoxDT != "") SDate.parseString(flight.EstChoxDT).millisSinceEpoch
    else if (flight.ActDT != "") SDate.parseString(flight.ActDT).millisSinceEpoch + timeToChoxMillis
    else if (flight.EstDT != "") SDate.parseString(flight.EstDT).millisSinceEpoch + timeToChoxMillis
    else SDate.parseString(flight.SchDT).millisSinceEpoch + timeToChoxMillis
    bestChoxTimeMillis
  }

  "bestChoxTime" >> {
    "Given an ApiFlight with only a scheduled time, " +
    "when we ask for the best chox time, " +
    "then we should get the actual chox time in millis" >> {
      
    }
  }

  "pcpFrom" >> {
    "Given an ApiFlight with a known gate and actual chox, " +
    "when we ask for the pcpFrom time, " +
    "then we should get actual chox + first pax off time + walk time" >> {

      val flight = apiFlight(
        actChox = "2017-01-01T00:20.00Z",
        terminal = "T1",
        gate = "2"
      )

      val walkTimes = Seq(
        WalkTime("1", "T1", 10000),
        WalkTime("2", "T1", 20000)
      )

      val wtp = walkTimeMillisProvider(walkTimes) _

      val result = pcpFrom(0, 0, 0)(wtp)(flight)
      val expected = MilliDate(1483230020000L) // 2017-01-01T00:20:20.00Z

      result === expected
    }
  }

  def apiFlight(sch: String = "", est: String = "", act: String = "", estChox: String = "", actChox: String = "",
                terminal: String = "", gate: String = "", stand: String = ""): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      SchDT = sch,
      EstDT = est,
      ActDT = act,
      EstChoxDT = estChox,
      ActChoxDT = actChox,
      Gate = gate,
      Stand = "",
      MaxPax = 1,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 1,
      AirportID = "",
      Terminal = terminal,
      rawICAO = "",
      rawIATA = "",
      Origin = "",
      PcpTime = 0
    )
}
