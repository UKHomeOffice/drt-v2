package services

import controllers.ArrivalGenerator.arrival
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared.api.{Arrival, WalkTime}
import drt.shared.coachTime.{CoachTransfer, DefaultCoachWalkTime, LhrCoachWalkTime}
import drt.shared.redlist.LhrRedListDatesImpl
import drt.shared.{MilliDate, PortCode}
import org.specs2.mutable.SpecificationLike

class PcpArrivalSpec extends SpecificationLike {

  import PcpArrival._

  private val oneMinuteMillis = 60000L

  "DRT-4607 parseWalkTimeWithMinuteRounding" +
    "We must round the walktimes to the nearest hour to keep the web client happy" +
    "See DRT-4607 for the why. Perhaps we will revisit" >> {
    "Given a valid walk time csv line string " +
      " AND the walktime is 45 seconds" +
      "then we should get back a WalkTime of 1 minutes" >> {
      val validCsvLine = "101,45,T1"

      val result = walkTimeFromStringWithRounding(validCsvLine)
      val expected = Some(WalkTime("101", T1, oneMinuteMillis))

      result === expected
    }
    "Given a valid walk time csv line string " +
      " AND the walktime is 65 seconds" +
      "then we should get back a WalkTime of 1 minutes" >> {
      val validCsvLine = "101,65,T1"

      val result = walkTimeFromStringWithRounding(validCsvLine)
      val expected = Some(WalkTime("101", T1, oneMinuteMillis))

      result === expected
    }
    "Given a valid walk time csv line string " +
      " AND the walktime is 125 seconds" +
      "then we should get back a WalkTime of 2 minutes (120000L)" >> {
      val validCsvLine = "101,125,T1"

      val result = walkTimeFromStringWithRounding(validCsvLine)
      val expected = Some(WalkTime("101", T1,  2 * oneMinuteMillis))

      result === expected
    }
  }


  "parseWalkTime" >> {
    "Given a valid walk time csv line string " +
      "then we should get back a WalkTime representing it" >> {
      val validCsvLine = "101,475,T1"

      val result = walkTimeFromString(validCsvLine)
      val expected = Some(WalkTime("101", T1, 475000L))

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
      val walkTimes: Map[(String, Terminal), Long] = Map()

      val result = walkTimeMillis(walkTimes)("2", T1)
      val expected = None

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for that WalkTime, " +
      "then we should get the millis from it" >> {
      val walkTimes: Map[(GateOrStand, Terminal), Long] = Map(("1", T1) -> 10000L)

      val result = walkTimeMillis(walkTimes)("1", T1)
      val expected = Some(10000)

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time millis for a different WalkTime, " +
      "then we should get None" >> {
      val walkTimes: Map[(GateOrStand, Terminal), Long] = Map(("1", T1) -> 10000L)

      val result = walkTimeMillis(walkTimes)("2", T1)
      val expected = None

      result === expected
    }

    "Given some WalkTimes, " +
      "when we ask for the walk time millis for one of them, " +
      "then we should get the millis for the matching WalkTime" >> {
      val walkTimes: Map[(GateOrStand, Terminal), Long] = Map(
        ("1", T1) -> 10000L,
        ("2", T1) -> 20000L,
        ("3", T1) -> 30000L)


      val result = walkTimeMillis(walkTimes)("2", T1)
      val expected = Some(20000)

      result === expected
    }
  }

  "bestChoxTime" >> {
    "Given an Arrival with only a scheduled time, " +
      "when we ask for the best chox time, " +
      "then we should get the scheduled time plus the time to chox in millis" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an estimated time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated time plus the time to chox in millis" >> {
      val flight = arrival(estDt = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with a touchdown (act) time, " +
      "when we ask for the best chox time, " +
      "then we should get the touchdown time plus the time to chox in millis" >> {
      val flight = arrival(actDt = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L + 10000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an estimated chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated chox time in millis" >> {
      val flight = arrival(estChoxDt = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L) // 2017-01-01T00:20.00Z

      result === expected
    }

    "Given an Arrival with an actual chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the actual chox time in millis" >> {
      val flight = arrival(actChoxDt = "2017-01-01T00:20.00Z")

      val result = bestChoxTime(10000L, flight)
      val expected = Some(1483230000000L) // 2017-01-01T00:20.00Z

      result === expected
    }
  }

  "pcpFrom" >> {
    "Given an Arrival with only a scheduled time, and no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + default walk time" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"))
      val walkTimes: Map[(String, Terminal), Long] = Map()

      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with an act chox time, and no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get act chox + first pax off time + default walk time" >> {
      val flight = arrival(actChoxDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"))
      val walkTimes: Map[(String, Terminal), Long] = Map()
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val actChoxMillis = 1483230000000L
      val expected = MilliDate(actChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with an act chox time, and a gate walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get act chox + first pax off time + gate walk time" >> {
      val t1 = T1
      val g2 = "2"
      val flight = arrival(terminal = t1, actChoxDt = "2017-01-01T00:20.00Z", gate = Option(g2))
      val gateWalkTimeMillis = 600000L
      val walkTimes: Map[(GateOrStand, Terminal), Long] = Map((g2, t1) -> gateWalkTimeMillis)

      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val actChoxMillis = 1483230000000L
      val expected = MilliDate(actChoxMillis + firstPaxOffMillis + gateWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, but no walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + default walk time" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"), stand = Option("2L"))
      val walkTimes: Map[(String, Terminal), Long] = Map()
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val wtp = walkTimeMillis(walkTimes) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(wtp, wtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + defaultWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and only a matching gate walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + gate walk time" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"), stand = Option("2L"))
      val gateWalkTimeMillis = 600000L
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gWtp = walkTimeMillis(Map(("2", T1) -> gateWalkTimeMillis)) _

      val sWtp = walkTimeMillis(Map()) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + gateWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and only a matching stand walk time, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + stand walk time" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"), stand = Option("2L"))
      val standWalkTimeMillis = 600000L
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gWtp = walkTimeMillis(Map()) _
      val sWtp = walkTimeMillis(Map(("2L", T1) -> standWalkTimeMillis)) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + standWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, a gate and stand, and both matching stand and gate walk times, " +
      "when we ask for the pcpFrom time, " +
      "then we should get scheduled + time to chox + first pax off time + stand walk time" >> {
      val flight = arrival(schDt = "2017-01-01T00:20.00Z", terminal = T1, gate = Option("2"), stand = Option("2L"))
      val timeToChoxMillis = 120000L // 2 minutes
      val firstPaxOffMillis = 180000L // 3 minutes
      val defaultWalkTimeMillis = 300000L // 5 minutes

      val gateWalkTimeMillis = 540000L
      val gWtp = walkTimeMillis(Map(("2", T1) -> gateWalkTimeMillis)) _

      val standWalkTimeMillis = 600000L
      val sWtp = walkTimeMillis(Map(("2L", T1) -> standWalkTimeMillis)) _

      def walkTimeForFlight(flight: Arrival): Long = gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis, DefaultCoachWalkTime)(flight)

      val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)

      val schMillis = 1483230000000L
      val expected = MilliDate(schMillis + timeToChoxMillis + firstPaxOffMillis + standWalkTimeMillis)

      result === expected
    }

    "Given an Arrival with a scheduled time, coach transfer detail and flight from red list country after T4 has become a red list terminal" >> {
      val timeToChoxMillis = 2 * oneMinuteMillis
      val firstPaxOffMillis = 3 * oneMinuteMillis
      val paxLoadingTime = 10 * oneMinuteMillis
      val coachTransferTime = 11 * oneMinuteMillis
      val walkTimeFromCoach = 15 * oneMinuteMillis
      val defaultWalkTimeMillis = 5 * oneMinuteMillis

      val gateWalkTimeMillis = 9 * oneMinuteMillis
      val gWtp = walkTimeMillis(Map(("2", T2) -> gateWalkTimeMillis)) _

      val standWalkTimeMillis = 10 * oneMinuteMillis
      val sWtp = walkTimeMillis(Map(("2L", T2) -> standWalkTimeMillis)) _

      def walkTimeForFlight(flight: Arrival): Long = {
        val transfer = CoachTransfer(T2, paxLoadingTime, coachTransferTime, walkTimeFromCoach)
        gateOrStandWalkTimeCalculator(gWtp, sWtp, defaultWalkTimeMillis, LhrCoachWalkTime(LhrRedListDatesImpl, List(transfer)))(flight)
      }

      "when we ask for the pcpFrom time for a red list flight at T2 scheduled after T4 has opened as a red list terminal"  >> {
        val flight = arrival(schDt = "2021-07-01T00:20.00Z", terminal = T2, gate = Option("2"), stand = Option("2L"), origin = PortCode("KBL"))

        "then we should get scheduled + time to chox + first pax off + stand walk time" >> {
          val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)
          val expected = MilliDate(flight.Scheduled + timeToChoxMillis + firstPaxOffMillis + paxLoadingTime + coachTransferTime + walkTimeFromCoach)
          result === expected
        }
      }

      "when we ask for the pcpFrom time for a red list flight at T2 scheduled when T3 has opened as a red list terminal"  >> {
        val flight = arrival(schDt = "2021-06-10T00:20.00Z", terminal = T2, gate = Option("2"), stand = Option("2L"), origin = PortCode("KBL"))

        "then we should get scheduled + time to chox + first pax off + coach load + coach transfer + coach gate walk time" >> {
          val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)
          val expected = MilliDate(flight.Scheduled + timeToChoxMillis + firstPaxOffMillis + paxLoadingTime + coachTransferTime + walkTimeFromCoach)
          result === expected
        }
      }

      "when we ask for the pcpFrom time for a red list flight at T2 scheduled before T3 has opened as a red list terminal"  >> {
        val flight = arrival(schDt = "2021-05-10T00:20.00Z", terminal = T2, gate = Option("2"), stand = Option("2L"), origin = PortCode("KBL"))

        "then we should get scheduled + time to chox + first pax off + coach load + coach transfer + coach gate walk time" >> {
          val result = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeForFlight)(flight)
          val expected = MilliDate(flight.Scheduled + timeToChoxMillis + firstPaxOffMillis + standWalkTimeMillis)
          result === expected
        }
      }
    }
  }
}
