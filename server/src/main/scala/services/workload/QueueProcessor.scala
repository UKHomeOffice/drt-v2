package services.workload

import scala.annotation.tailrec


case class QueuePassenger(load: Int, joinTime: Int)

object QueuePassenger {
  def apply(load: Int): QueuePassenger = QueuePassenger(load, 0)
}


object QueueProcessor {
  @tailrec
  def applyCapacity(minute: Int, capacitySeconds: Int, queue: List[QueuePassenger], waitTime: Int = 0): (List[QueuePassenger], Int) = {
    def calcWaitTime(previousWaitTime: Int, joinTime: Int): Int = minute - joinTime match {
      case x if x > previousWaitTime => x
      case _ => previousWaitTime
    }

    queue match {
      case Nil => (Nil, 0)

      case currentPassenger :: tail =>
        val paxIsFullyProcessed = capacitySeconds >= currentPassenger.load
        val newWaitTime = calcWaitTime(waitTime, currentPassenger.joinTime)

        if (paxIsFullyProcessed) {
          val newCapacity = capacitySeconds - currentPassenger.load
          if (newCapacity > 0 && tail.nonEmpty) {
            applyCapacity(minute, newCapacity, tail, newWaitTime)
          } else {
            (tail, newWaitTime)
          }
        } else {
          val updatedPax = currentPassenger.copy(load = currentPassenger.load - capacitySeconds)
          (updatedPax :: tail, newWaitTime)
        }
    }
  }

  def processQueue(capacityMinutes: IndexedSeq[List[Int]], paxLoadMinutes: Seq[List[QueuePassenger]]): (List[QueuePassenger], List[Int], List[Int]) = {
    val (queue, waitTimes, queueSizes) = paxLoadMinutes.zipWithIndex.foldLeft((List.empty[QueuePassenger], List.empty[Int], List.empty[Int])) {
      case ((incomingPax, previousWaits, previousQueueSizes), (incoming, minute)) =>
        val startQueueForMinute = incomingPax ::: incoming.map(_.copy(joinTime = minute))
        val (endQueueForMinute, maxWait) = applyCapacity(minute, capacityMinutes(minute).sum, startQueueForMinute)
        (endQueueForMinute, maxWait :: previousWaits, endQueueForMinute.size :: previousQueueSizes)
    }
    (queue, waitTimes.reverse, queueSizes.reverse)
  }
}
