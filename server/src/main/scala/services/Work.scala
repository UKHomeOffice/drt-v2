package services

import scala.collection.immutable

case class ProcessedQueue(sla: Int,
                          numberOfMinutes: Int,
                          completedBatches: List[ProcessedBatchOfWork],
                          leftover: BatchOfWork,
                          override val queueByMinute: List[Double]) extends ProcessedWorkLike {
  override lazy val waits: List[Int] = {
    val allWaits = (completedWaits ::: incompleteBatchWaits)
      .groupBy { case (minute, _) => minute }
      .map { case (minute, waits) => (minute, waits.map(_._2).max) }
      .toList
      .sortBy { case (minute, _) => minute }

    allWaits.map(_._2)
  }
  override lazy val excessWait: Double = completedBatches.map(_.totalExcessWait(sla)).sum + leftoverExcessWaits(sla)
  override val util: List[Double] = List()
  override val residual: IndexedSeq[Double] = IndexedSeq()

  override def incrementTotalWait(toAdd: Double): ProcessedWorkLike = this

  override def incrementExcessWait(toAdd: Double): ProcessedWorkLike = this

  lazy val leftoverWaits: Double = leftover.loads.map(w => w.load * (numberOfMinutes - w.createdAt)).sum

  lazy val totalWait: Double = completedBatches.map(_.totalWait).sum + leftoverWaits

  def leftoverExcessWaits(sla: Int): Double = leftover.loads.map(w => w.load * Math.min(0, (numberOfMinutes - w.createdAt) - sla)).sum

  lazy val incompleteBatchWaits: List[(Int, Int)] =
    leftover.loads.flatMap { work =>
      (work.createdAt until numberOfMinutes).map(pm => (pm, numberOfMinutes - pm))
    }

  lazy val completedWaits: List[(Int, Int)] =
    completedBatches.flatMap {
      case ProcessedBatchOfWork(minuteProcessed, work) =>
        work.loads.flatMap { x =>
          val processingMinutes = x.createdAt to minuteProcessed
          processingMinutes.map(processingMinute => {
            val waitForProcessingMinute = minuteProcessed - processingMinute
            (processingMinute, waitForProcessingMinute)
          })
        }
    }
}

case class ProcessedBatchOfWork(minuteProcessed: Int, batch: BatchOfWork) {
  lazy val totalWait: Double = batch.loads.map(_.processed.map(_.loadedWait).sum).sum

  def totalExcessWait(sla: Int): Double = batch.loads.map(_.processed.map(_.excessLoadedWait(sla)).sum).sum
}

case class QueueCapacity(desks: List[Int]) {
  def processMinutes(sla: Int, minutes: List[Double]): ProcessedQueue = {
    val workWithDesks: immutable.Seq[(Work, Capacity)] = minutes.zipWithIndex
      .map { case (wl, minute) => Work(wl, minute) }
      .zip(desks)
      .map { case (w, d) => (w, Capacity(d, w.createdAt)) }

    val (processedMinutes, queueSizeByMinute, leftOver) = workWithDesks
      .foldLeft((List[ProcessedBatchOfWork](), List[Double](), BatchOfWork(List()))) {
        case ((processedBatchesSoFar, queueSizeByMinute, spillover), (work, desksOpen)) =>
          val (processedBatch, _) = desksOpen.process(spillover + work)
          val processedBatches = processedBatchesSoFar :+ ProcessedBatchOfWork(work.createdAt, processedBatch.completed)
          val queueSize = processedBatch.outstanding.loads.map(_.load).sum
          (processedBatches, queueSize :: queueSizeByMinute, processedBatch.outstanding)
      }

    ProcessedQueue(sla, minutes.length, processedMinutes, leftOver, queueSizeByMinute.reverse)
  }
}

case class Capacity(value: Double, availableAt: Int) {
  lazy val isEmpty: Boolean = value == 0

  def remove(load: Double): Capacity = this.copy(value = Math.max(value - load, 0))

  def process(work: BatchOfWork): (BatchOfWork, Capacity) = work.loads.foldLeft((BatchOfWork(List()), this)) {
    case ((processedSoFar, capacityLeft), nextWork) =>
      val (newProcessed, newCap) = if (!capacityLeft.isEmpty)
        nextWork.process(capacityLeft)
      else
        (nextWork, capacityLeft)

      (processedSoFar + newProcessed, newCap)
  }
}

case class ProcessedLoad(load: Double, createdAt: Int, processedAt: Int) {
  lazy val waitTime: Int = processedAt - createdAt
  lazy val loadedWait: Double = waitTime * load

  def excessLoadedWait(sla: Int): Double = Math.min(waitTime - sla, 0) * load
}

object Work {
  def apply(load: Double, createdAt: Int): Work = Work(load, createdAt, List())
}

case class Work(load: Double, createdAt: Int, processed: List[ProcessedLoad]) {
  lazy val completed: Boolean = load == 0

  def process(capacity: Capacity): (Work, Capacity) = {
    val loadProcessed = Math.min(capacity.value, load)
    val processedLoad = ProcessedLoad(loadProcessed, createdAt, capacity.availableAt)
    val newWork = this.copy(load = load - loadProcessed, processed = processedLoad :: processed)
    val newCapacity = capacity.remove(load)
    (newWork, newCapacity)
  }
}

object BatchOfWork {
  val empty: BatchOfWork = BatchOfWork(List())
}

case class BatchOfWork(loads: List[Work]) {
  lazy val completed: BatchOfWork = BatchOfWork(loads.filter(_.completed))
  lazy val outstanding: BatchOfWork = BatchOfWork(loads.filter(!_.completed))

  def +(loadsToAdd: BatchOfWork): BatchOfWork = this.copy(loads = (loads ::: loadsToAdd.loads).sortBy(_.createdAt))

  def +(loadToAdd: Work): BatchOfWork = this.copy(loads = (loadToAdd :: loads).sortBy(_.createdAt))
}
