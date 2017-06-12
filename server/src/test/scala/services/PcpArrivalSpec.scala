package services

import com.typesafe.config.ConfigFactory
import drt.shared.{Arrival, MilliDate}
import org.specs2.mutable.SpecificationLike

class PcpArrivalSpec extends SpecificationLike {

  import PcpArrival._

  "parseWalkTime" >> {
    "Given a valid walk time csv line string " +
      "then we should get back a WalkTime representing it" >> {
      val validCsvLine = "101,475,T1"

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
      val walkTimes: Map[(String, String), Long] = Map()

      val result = walkTimeMillis(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for that WalkTime, " +
      "then we should get the millis from it" >> {
      val walkTimes = Map(("1", "T1") -> 10000L)

      val result = walkTimeMillis(walkTimes)("1", "T1")
      val expected = Some(10000)

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for a different WalkTime, " +
      "then we should get None" >> {
      val walkTimes = Map(("1", "T1") -> 10000L)

      val result = walkTimeMillis(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given some WalkTimes, " +
      "when we ask for the walk time millis for one of them, " +
      "then we should get the millis for the matching WalkTime" >> {
      val walkTimes = Map(
        ("1", "T1") -> 10000L,
        ("2", "T1") -> 20000L,
        ("3", "T1") -> 30000L)


      val result = walkTimeMillis(walkTimes)("2", "T1")
      val expected = Some(20000)

      result === expected
    }
  }

  "bestChoxTime" >> {
    "Given an Arrival with only a scheduled time, " +
      "when we ask for the best chox time, " +
      "then we should get the scheduled time plus the time to chox in millis" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an estimated time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated time plus the time to chox in millis" >> {
      val flight = apiFlight(est = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with a touchdown (act) time, " +
      "when we ask for the best chox time, " +
      "then we should get the touchdown time plus the time to chox in millis" >> {
      val flight = apiFlight(act = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an estimated chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated chox time in millis" >> {
      val flight = apiFlight(estChox = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an actual chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the actual chox time in millis" >> {
      val flight = apiFlight(actChox = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L) // 2017-01-01T00:20.00Z

      result === expected
    }
  }

  "pcpFrom" >> {
    "Given an Arrival with only a scheduled time, and no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + default walk time" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2")
      val walkTimes: Map[(String, String), Long] = Map()

      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with an act chox time, and no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get act chox + first pax off time + default walk time" >> {
      val flight = apiFlight(actChox = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2")
      val walkTimes: Map[(String, String), Long] = Map()
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val actChoxMillis = 1483230000000L
      val expected = MilliDate(actChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with an act chox time, and a gate walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get act chox + first pax off time + gate walk time" >> {
      val t1 = "T1"
      val g2 = "2"
      val flight = apiFlight(actChox = "2017-01-01T00:20.00Z", terminal = t1, gate = g2)
      val gateWalkTimeMillis = 600000L
      val walkTimes = Map((g2, t1) -> gateWalkTimeMillis)

      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val actChoxMillis = 1483230000000L
      val expected = MilliDate(actChoxMillis + firstPaxOffMillis + gateWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, but no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + default walk time" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2", stand = "2L")
      val walkTimes: Map[(String, String), Long] = Map()
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and only a matching gate walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + gate walk time" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2", stand = "2L")
      val gateWalkTimeMillis = 600000L
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gWtp = walkTimeMillis(Map(("2", "T1") -> gateWalkTimeMillis)) _

      val sWtp = walkTimeMillis(Map()) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + gateWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and only a matching stand walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + stand walk time" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2", stand = "2L")
      val standWalkTimeMillis = 600000L
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gWtp = walkTimeMillis(Map()) _
      val sWtp = walkTimeMillis(Map(("2L", "T1") -> standWalkTimeMillis)) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + standWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and both matching stand and gate walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + stand walk time" >> {
      val flight = apiFlight(sch = "2017-01-01T00:20.00Z", terminal = "T1", gate = "2", stand = "2L")
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gateWalkTimeMillis = 540000L
      val gWtp = walkTimeMillis(Map(("2", "T1") -> gateWalkTimeMillis)) _

      val standWalkTimeMillis = 600000L
      val sWtp = walkTimeMillis(Map(("2L", "T1") -> standWalkTimeMillis)) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + standWalkTimeMillis)

      result === expected
    }
  }

  def apiFlight(sch: String = "", est: String = "", act: String = "", estChox: String = "", actChox: String = "",
                terminal: String = "", gate: String = "", stand: String = ""): Arrival =
    Arrival(
      Operator = "",
      Status = "",
      SchDT = sch,
      EstDT = est,
      ActDT = act,
      EstChoxDT = estChox,
      ActChoxDT = actChox,
      Gate = gate,
      Stand = stand,
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
