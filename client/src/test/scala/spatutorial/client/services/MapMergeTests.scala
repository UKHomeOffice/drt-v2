package spatutorial.client.services

import utest._

object MapMergeTests extends TestSuite {
  import RootModel._
  def tests = TestSuite {
    "Map merge" - {
      val m1 = Map("T1" -> Map("EEA" -> Seq(1)))
      val m2 = Map("T1" -> Map("eGates" -> Seq(2)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(2)))

      assert(expected == cleaned)
    }
    "Map merge 2" - {
      val m1 = Map("T1" -> Map("eGates" -> Seq(3)))
      val m2 = Map("T1" -> Map("EEA" -> Seq(1)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(3)))

      assert(expected == cleaned)

    }
    "Map merge 3" - {
      val m1 = Map("T1" -> Map("eGates" -> Seq(3)))
      val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      assert(expected == cleaned)
    }
    "Map merge 4" - {
      val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)))
      val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      assert(expected == cleaned)
    }
    "Map merge 5" - {
      val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))
      val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))

      assert(expected == cleaned)
    }
    "Map merge 6" - {
      val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))
      val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

      val cleaned = mergeTerminalQueues(m1, m2)
      val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("eGates" -> Seq(1), "EEA" -> Seq(1)))

      assert(expected == cleaned)
    }
  }
}
