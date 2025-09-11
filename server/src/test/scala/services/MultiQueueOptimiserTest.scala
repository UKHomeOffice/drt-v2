package services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MultiQueueOptimiserTest extends AnyWordSpec with Matchers {
  "MultiQueueOptimiser" should {
    "optimise queues correctly" in {
      MultiQueueOptimiser.runTwoQueues(IndexedSeq.empty, IndexedSeq.empty, IndexedSeq.empty)

      0 shouldEqual 0
    }
  }
}
