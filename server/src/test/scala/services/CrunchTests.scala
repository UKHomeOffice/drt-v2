package services

import drt.shared._
import org.joda.time.DateTime
import utest._

import scala.collection.immutable.Seq

object CrunchStructureTests extends TestSuite {
  def tests = TestSuite {
    "given workloads by the minute we can get them in t minute chunks and take the sum from each chunk" - {
      val workloads = WL(1, 2) :: WL(2, 3) :: WL(3, 4) :: WL(4, 5) :: Nil

      val period: List[WL] = WorkloadsHelpers.workloadsByPeriod(workloads, 2).toList
      assert(period == WL(1, 5) :: WL(3, 9) :: Nil)
    }

    "Given a sequence of workloads we should return the midnight on the day of the earliest workload" - {
      val queueWorkloads = Seq((Seq(WL(getMilisFromDate(2016, 11, 1, 13, 0), 1.0), WL(getMilisFromDate(2016, 11, 1, 14, 30), 1.0), WL(getMilisFromDate(2016, 11, 1, 14, 45), 1.0)), Seq[Pax]()))

      val expected = getMilisFromDate(2016, 11, 1, 0, 0);

      val result = new WorkloadsHelpers {}.midnightBeforeEarliestWorkload(queueWorkloads)
      assert(expected == result)
    }
  }

  private def getMilisFromDate(year: Int, monthOfYear: Int, dayOfMonth: Int, hourOfDay: Int, minuteOfHour: Int) = {
    new DateTime(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour).getMillis
  }
}
