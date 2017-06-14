package services

import akka.util.Timeout
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class MaxDesksSpec extends Specification {

  implicit val timeout: Timeout = Timeout(5 seconds)
  "The desks recommended by the crunch should not excede the max desks for a queue" in
  {

    val workloads = List.fill[Double](30)(15.0)
    val repeat = List.fill[Int](workloads.length) _

    val minDesks = repeat(2)
    val maxDesks = repeat(2)

    val crunchCalculator = new CrunchCalculator {

    }

    val tryCrunchRes = crunchCalculator.crunch("T1", "EEA", workloads, 25, repeat(2), repeat(2))

    tryCrunchRes match {
      case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
        deskRecs == repeat(2)
      case Failure(f) =>
        println(f.getStackTrace)
        println(f.getMessage)

        false
    }
  }
}
