package services.inputfeeds

import controllers._
import utest._



object LHRCsvTests extends TestSuite {

  def tests = TestSuite {
    "can load LHR csv" - {
      val feed = LHRFlightFeed()
      // val csvLoad = LHRFlightFeflight.statused(null) csvLoad.csvContent(0)

//      println("nonoflights, " + feed.lhrFlights.toList.map {
//        case Success(s) => s
//        case Failure(f) => f.toString
//          assert(false)
//      }.mkString("\n"))
    }
    "can parse the backwards LHR date format like '05:19 22/10/2016' to a sane joda time" - {
      val dateString = "05:19 22/10/2016"
      import org.joda.time.DateTime
      val parsed = LHRFlightFeed.parseDateTime(dateString)
      val expected: DateTime = new DateTime(2016, 10, 22, 5, 19)
      assert(parsed == expected)
    }
    "can parse the backwards LHR date format like with a 24hr time '20:19 22/10/2016' to a sane joda time" - {
      val dateString = "20:19 22/10/2016"
      import org.joda.time.DateTime
      val parsed = LHRFlightFeed.parseDateTime(dateString)
      val expected: DateTime = new DateTime(2016, 10, 22, 20, 19)
      assert(parsed == expected)
    }
  }
}
