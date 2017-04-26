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
      val expected = Some(WalkTime("101", "T1", 475))

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

  "walkTimeSecondsProvider" >> {
    "Given no WalkTimes, " +
      "when we ask for the walk time seconds for a WalkTime, " +
      "then we should get None" >> {
      val walkTimes = Seq()

      val result = walkTimeSecondsProvider(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time seconds for that WalkTime, " +
      "then we should get the seconds from it" >> {
      val walkTimes = Seq(WalkTime("1", "T1", 10))

      val result = walkTimeSecondsProvider(walkTimes)("1", "T1")
      val expected = Some(10)

      result === expected
    }

    "Given a WalkTime, " +
      "when we ask for the walk time seconds for a different WalkTime, " +
      "then we should get None" >> {
      val walkTimes = Seq(WalkTime("1", "T1", 10))

      val result = walkTimeSecondsProvider(walkTimes)("2", "T1")
      val expected = None

      result === expected
    }

    "Given some WalkTimes, " +
      "when we ask for the walk time seconds for one of them, " +
      "then we should get the seconds for the matching WalkTime" >> {
      val walkTimes = Seq(
        WalkTime("1", "T1", 10),
        WalkTime("2", "T1", 20),
        WalkTime("3", "T1", 30)
      )

      val result = walkTimeSecondsProvider(walkTimes)("2", "T1")
      val expected = Some(20)

      result === expected
    }
  }
  "pcpFrom" >> {
    "Given an ApiFlight with a known gate and actual chox, " +
    "when we ask for the pcpFrom time, " +
    "then we should get actual chox + first pax off time + walk time" >> {
      def pcpFrom(firstPaxOff: (ApiFlight) => Long)(walkTimesProvider: WalkTimeSecondsProvider)(flight: ApiFlight): MilliDate = {
        val timeToChoxMillis = 120000L
        val defaultWalkTimeSeconds = 300
        val bestChoxTimeMillis = if (flight.ActChoxDT != "") SDate.parseString(flight.ActChoxDT).millisSinceEpoch
        else if (flight.EstChoxDT != "") SDate.parseString(flight.EstChoxDT).millisSinceEpoch
        else if (flight.ActDT != "") SDate.parseString(flight.ActDT).millisSinceEpoch + timeToChoxMillis
        else if (flight.EstDT != "") SDate.parseString(flight.EstDT).millisSinceEpoch + timeToChoxMillis
        else SDate.parseString(flight.SchDT).millisSinceEpoch + timeToChoxMillis

        val walkTimeMillis = walkTimesProvider(flight.Stand, flight.Terminal).getOrElse(defaultWalkTimeSeconds) * 1000L

        MilliDate(bestChoxTimeMillis + walkTimeMillis)
      }

      1 === 2
    }
  }
}
