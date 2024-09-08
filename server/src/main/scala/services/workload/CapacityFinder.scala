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
      case pax :: tail =>
        val paxIsFullyProcessed = capacitySeconds >= pax.load
        val newWaitTime = calcWaitTime(waitTime, pax.joinTime)

        if (paxIsFullyProcessed) {
          val newCapacity = capacitySeconds - pax.load
          if (newCapacity > 0 && tail.nonEmpty)
            applyCapacity(minute, newCapacity, tail, newWaitTime)
          else
            (tail, newWaitTime)
        } else {
          val updatedPax = pax.copy(load = pax.load - capacitySeconds)
          (updatedPax :: tail, calcWaitTime(waitTime, newWaitTime))
        }
    }
  }

  def processQueue(desks: Int, paxLoadMinutes: Seq[List[QueuePassenger]]): (List[QueuePassenger], List[Int]) = {
    val (queue, waitTimes) = paxLoadMinutes.zipWithIndex.foldLeft((List.empty[QueuePassenger], List.empty[Int])) {
      case ((queue, Nil), (incoming, minute)) =>
        val (newQueue, maxWait) = applyCapacity(minute, 60 * desks, queue ::: incoming.map(_.copy(joinTime = minute)), 0)
        (newQueue, List(maxWait))

      case ((queue, currentWait :: previousWaits), (incoming, minute)) =>
        val (newQueue, maxWait) = applyCapacity(minute, 60 * desks, queue ::: incoming.map(_.copy(joinTime = minute)), currentWait)
        (newQueue, maxWait :: currentWait :: previousWaits)
    }
    (queue, waitTimes.reverse)
  }
}
