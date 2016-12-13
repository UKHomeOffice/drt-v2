package services

import controllers.EGateBankCrunchTransformations
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Seq}

object EGateBanksTests extends TestSuite {


  def tests = TestSuite {
    def intoBanksOf5WithSlaOf10 = EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(5, 10) _

    "Given a rounder to nearest 10, " +
      "When we pass it [1,5,10.11], " +
      "Then we should get back [10, 10, 10, 20] " - {
      val nums = Seq(1, 5, 10, 11)
      val expected = Seq(10, 10, 10, 20)

      def roundToNearest10 = EGateBankCrunchTransformations.roundUpToNearestMultipleOf(10) _
      val result = nums.map(roundToNearest10)

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the recommendation is 1, " +
      "Then it should be 1" - {

      val crunchResult = CrunchResult(IndexedSeq(1), Seq(1))

      val expected = CrunchResult(IndexedSeq(1), Seq(1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the recommendation is 6, " +
      "Then it should be rounded up to 10" - {

      val crunchResult = CrunchResult(IndexedSeq(6), Seq(1))

      val expected = CrunchResult(IndexedSeq(2), Seq(1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the recommendations are [6, 11, 15, 21], " +
      "Then it should be rounded up to [10, 15, 15, 25]" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1))

      val expected = CrunchResult(IndexedSeq(2, 3, 3, 5), Seq(1, 1, 1, 1))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(20, 20, 20, 20))

      assert(result == expected)
    }

    "Given a crunch result for the eGates Queue, " +
      "When the desk recommendations have been rounded up, " +
      "Then the wait times should reflect the revised desk recs" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1, 1, 1, 1))

      val expected = CrunchResult(IndexedSeq(2, 3, 3, 5), Seq(1, 2, 3, 4))
      val result = intoBanksOf5WithSlaOf10(crunchResult, Seq(90, 90, 90, 90))

      assert(result == expected)
    }
    "Given a crunch result for the eGates Queue, " +
      "When the number of eGates per bank is 10," +
      "Then the revised desk recs should be multiples of 10" - {

      val crunchResult = CrunchResult(IndexedSeq(6, 11, 15, 21), Seq(1, 1, 1, 1))

      val expected = CrunchResult(IndexedSeq(1, 2, 2, 3), Seq(1, 2, 3, 4))
      val result = EGateBankCrunchTransformations.groupEGatesIntoBanksWithSla(10, 10)(crunchResult, Seq(90, 90, 90, 90))

      assert(result == expected)
    }

    "Given some workload and desks " +
      "When we run a simulation " +
      "Then we should see wait times corresponding to those desks" - {
      val workload = List(
        15d, 5d, 5d, 5d, 5d,
        5d, 5d, 5d, 5d, 5d,
        5d, 5d, 5d, 5d, 5d
      )
      val eGateBanks = List(1)

      val airportConfig = AirportConfig(
        queues = Seq(),
        slaByQueue = Map("eeaDesk" -> 5),
        terminalNames = Seq(),
        defaultPaxSplits = List(),
        defaultProcessingTimes = Map()
      )

      val result = WorkloadSimulation.processWork(airportConfig)("T1", "eeaDesk", workload, eGateBanks)
      val expected = SimulationResult(Vector(
        DeskRec(0, 1), DeskRec(1, 1), DeskRec(2, 1), DeskRec(3, 1), DeskRec(4, 1),
        DeskRec(5, 1), DeskRec(6, 1), DeskRec(7, 1), DeskRec(8, 1), DeskRec(9, 1),
        DeskRec(10, 1), DeskRec(11, 1), DeskRec(12, 1), DeskRec(13, 1), DeskRec(14, 1)
      ), Vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 14))

      assert(result == expected)
    }

    "Given some workload and egate banks " +
      "When we run a simulation " +
      "Then we should see wait times corresponding to those banks of egates" - {
      val workload = List(
        15d, 5d, 5d, 5d, 5d,
        5d, 5d, 5d, 5d, 5d,
        5d, 5d, 5d, 5d, 5d
      )
      val eGateBanks = List(1)

      val airportConfig = AirportConfig(
        queues = Seq(),
        slaByQueue = Map("eGate" -> 5),
        terminalNames = Seq(),
        defaultPaxSplits = List(),
        defaultProcessingTimes = Map()
      )

      val result = WorkloadSimulation.processWork(airportConfig)("T1", "eGate", workload, eGateBanks)
      val expected = SimulationResult(Vector(
        DeskRec(0, 1), DeskRec(1, 1), DeskRec(2, 1), DeskRec(3, 1), DeskRec(4, 1),
        DeskRec(5, 1), DeskRec(6, 1), DeskRec(7, 1), DeskRec(8, 1), DeskRec(9, 1),
        DeskRec(10, 1), DeskRec(11, 1), DeskRec(12, 1), DeskRec(13, 1), DeskRec(14, 1)
      ), Vector(1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2))

      assert(result == expected)
    }
  }
}

