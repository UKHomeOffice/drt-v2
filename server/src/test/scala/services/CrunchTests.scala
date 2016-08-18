package services

import java.io.InputStream
import javax.script._

import org.renjin.sexp.DoubleVector
import spatutorial.shared._
import utest._

import scala.util.Random



object CrunchTests extends TestSuite {
  def tests = TestSuite {
    'canCrunch - {
      val blockWidth = 15
      val workloads = Iterator.continually(Random.nextDouble() * 20).take(100 * blockWidth).toSeq
      val cr = TryRenjin.crunch(workloads)
//      println(cr)
      assert(true)
    }
    'anotherOne - {
      val workload = (0 until 30 * 15) map (_ => 23.0d)
      val cr = TryRenjin.crunch(workload)
      val cr2 = TryRenjin.crunch(workload)
      val cr3 = TryRenjin.crunch(Nil)
      assert(true)
    }
  }
}

