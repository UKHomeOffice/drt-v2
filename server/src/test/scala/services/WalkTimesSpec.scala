package services

import com.typesafe.config.ConfigFactory
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
}
