package services

import java.io.InputStream
import javax.script._

import org.renjin.sexp.DoubleVector
import spatutorial.shared._
import utest._

import scala.util.Random


object CrunchStructureTests extends TestSuite {
  def tests = TestSuite {
    "given workloads by the minute we can get them in t minute chunks and take the sum from each chunk" - {
      val workloads = QueueWorkloads("eea",
        workloadsByMinute = WL(1, 2) :: WL(2, 3) :: WL(3, 4) :: WL(4, 5) :: Nil,
        paxByMinute = Nil
        //          deskRec = Nil
      )

      val period: List[WL] = workloads.workloadsByPeriod(2).toList
      assert(period == WL(1, 5) :: WL(3, 9) :: Nil)
    }
  }
}

object CrunchTests extends TestSuite {
  def tests = TestSuite {
    //    'canCrunch - {
    //      val blockWidth = 15
    //      val workloads = Iterator.continually(Random.nextDouble() * 20).take(100 * blockWidth).toSeq
    //      val cr = TryRenjin.crunch(workloads)
    //      println(cr)
    //      assert(true)
    //    }
    //    'anotherOne - {
    //      val workload = (0 until 30 * 15) map (_ => 23.0d)
    //      val cr = TryRenjin.crunch(workload)
    //      val cr2 = TryRenjin.crunch(workload)
    //      val cr3 = TryRenjin.crunch(Nil)
    //      assert(true)
    //    }
    'canUseCsvForWorkloadInput - {
      val bufferedSource = scala.io.Source.fromURL(
       getClass.getResource("/optimiser-LHR-T2-NON-EEA-2016-09-12_121059-in-out.csv"))
      val recs: List[Array[String]] = bufferedSource.getLines.map { l =>
        l.split(",").map(_.trim)
      }.toList

      val workloads = recs.map(_ (0).toDouble)
      val minDesks = recs.map(_ (1).toInt)
      val maxDesks = recs.map(_ (2).toInt)
      val recDesks = recs.map(_ (3).toInt)

      val cr = TryRenjin.crunch(workloads, minDesks, maxDesks)
      println(cr.recommendedDesks.toString())
      println(recDesks.toVector)
    }
  }
}

