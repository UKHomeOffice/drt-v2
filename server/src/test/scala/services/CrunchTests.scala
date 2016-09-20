package services

import java.io.InputStream
import javax.script._

import org.renjin.sexp.DoubleVector
import spatutorial.shared._
import utest._

import scala.util.Random



object CrunchTests extends TestSuite {
  def tests = TestSuite {
//    'canUseCsvForWorkloadInput - {
//      val bufferedSource = scala.io.Source.fromFile("optimiser-LHR-T2-NON-EEA-2016-09-12_121059-in-out.csv")
//      val recs: List[Array[String]] = bufferedSource.getLines.map { l =>
//        l.split(",").map(_.trim)
//      }.toList
//
//      val workloads = recs.map(_(0).toDouble)
//      val minDesks = recs.map(_(1).toInt)
//      val maxDesks = recs.map(_(2).toInt)
//      val recDesks = recs.map(_(3).toInt)
//
//      val cr = TryRenjin.crunch(workloads, minDesks, maxDesks)
//      println(cr.recommendedDesks.toString())
//      println(recDesks.toVector)
//    }
  }
}

