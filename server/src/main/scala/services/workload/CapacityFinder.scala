package services.workload

import scala.annotation.tailrec


case class QueuePassenger(load: Int, joinTime: Int)

object QueuePassenger {
  def apply(load: Int): QueuePassenger = QueuePassenger(load, 0)
}


object CapacityFinder {
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
          (updatedPax :: tail, calcWaitTime(waitTime, currentPassenger.joinTime))
        }
    }
  }

  def processQueue(desks: Int, paxLoadMinutes: Seq[List[QueuePassenger]]): (List[QueuePassenger], List[Int]) = {
    val (queue, waitTimes) = paxLoadMinutes.zipWithIndex.foldLeft((List.empty[QueuePassenger], List.empty[Int])) {
      case ((queue, previousWaits), (incoming, minute)) =>
        val updatedQueue = queue ::: incoming.map(_.copy(joinTime = minute))
        val (newQueue, maxWait) = applyCapacity(minute, 60 * desks, updatedQueue)
        (newQueue, maxWait :: previousWaits)
    }
    (queue, waitTimes.reverse)
  }
}
