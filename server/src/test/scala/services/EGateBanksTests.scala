package services

import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Seq}

object EGateBanksTests extends TestSuite {
  object EGateBankCrunchTransformations {

    def groupEGatesIntoBanksWithSla(desksInBank: Int, sla: Int)(crunchResult: CrunchResult, workloads: Seq[Double]): CrunchResult = {
      val recommendedDesks = crunchResult.recommendedDesks.map(roundUpToNearestMultipleOf(desksInBank))
      val optimizerConfig = OptimizerConfig(sla)
      val simulationResult = TryRenjin.processWork(workloads, recommendedDesks, optimizerConfig)

      crunchResult.copy(
        recommendedDesks = recommendedDesks,
        waitTimes = simulationResult.waitTimes
      )
    }

    private def roundUpToNearestMultipleOf(multiple: Int)(number: Int) = math.ceil(number.toDouble / multiple).toInt * multiple
  }


  def tests = TestSuite {
    def intoBanksOf5WithSlaOf10 = EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(5, 10) _
    "Given a crunch result for the eGates Queue, " +
      "When the recommendation is 1, " +
      "Then it should be rounded up to 5" - {

      val crunchResult = CrunchResult(IndexedSeq(1), Seq(1))

      val expected = CrunchResult(IndexedSeq(5), Seq(1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the recommendation is 6, " +
      "Then it should be rounded up to 10" - {

      val crunchResult = CrunchResult(IndexedSeq(6), Seq(1))

      val expected = CrunchResult(IndexedSeq(10), Seq(1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the recommendations are [6, 11, 15, 21], " +
      "Then it should be rounded up to [10, 15, 15, 25]" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1))

      val expected = CrunchResult(IndexedSeq(10, 15, 15, 25), Seq(1, 1, 1, 1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20, 20, 20, 20))

      assert(result == expected)
    }

    "Given a crunch result for the eGates Queue, " +
      "When the desk recommendations have been rounded up, " +
      "Then the wait times should reflect the revised desk recs" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1, 1, 1, 1))

      val expected = CrunchResult(IndexedSeq(10, 15, 15, 25), Seq(1, 2, 3, 4))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(90, 90, 90, 90))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the number of eGates per bank is 10," +
      "Then the revised desk recs should be multiples of 10" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1, 1, 1, 1))

      val expected = CrunchResult(IndexedSeq(10, 20, 20, 30), Seq(1, 2, 3, 4))
      val result = EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(10, 10)(crunchResult, Seq(90, 90, 90, 90))

      assert(result == expected)
    }
  }

}

