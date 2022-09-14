package services

import scala.collection.immutable


case class ProcessedQueue(sla: Int,
                          numberOfMinutes: Int,
                          allBatches: List[ProcessedBatchOfWork],
                          leftover: BatchOfWork,
                          override val queueByMinute: List[Double]) extends ProcessedWorkLike {
  val completedBatches: List[ProcessedBatchOfWork] = allBatches.map(p => p.copy(batch = p.batch.completed))

  override lazy val waits: List[Int] =
    allBatches.foldLeft(List[Int]()) {
      case (waits, batch) => waits :+ batch.maxWait.getOrElse(0)
    }
  override lazy val excessWait: Double = completedBatches.map(_.totalExcessWait(sla)).sum + leftoverExcessWaits(sla)
  override val util: List[Double] = List()
  override val residual: IndexedSeq[Double] = IndexedSeq()

  override def incrementTotalWait(toAdd: Double): ProcessedWorkLike = this

  override def incrementExcessWait(toAdd: Double): ProcessedWorkLike = this

  lazy val leftoverWaits: Double = leftover.loads.map(w => w.load * (numberOfMinutes - w.createdAt)).sum

  lazy val totalWait: Double = completedBatches.map(_.totalWait).sum + leftoverWaits

  def leftoverExcessWaits(sla: Int): Double = leftover.loads.map(w => w.load * Math.max(0, (numberOfMinutes - w.createdAt) - sla)).sum

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

  lazy val maxWait: Option[Int] =
    if (batch.nonEmpty)
      Option(minuteProcessed - batch.loads.map(_.createdAt).min)
    else
      None

  def totalExcessWait(sla: Int): Double = batch.completed.loads.map(_.processed.map(_.excessLoadedWait(sla)).sum).sum
}

case class QueueCapacity(capacity: List[Int]) {
  def processMinutes(sla: Int, work: List[Double]): ProcessedQueue = {
    if (capacity.length != work.length) {
      throw new Exception(s"capacity & work lengths don't match: ${capacity.length} vs ${work.length}")
    }

    val workWithDesks: immutable.Seq[(Work, Capacity)] = work.zipWithIndex
      .map { case (wl, minute) => Work(wl, minute) }
      .zip(capacity)
      .map { case (w, d) => (w, Capacity(d, w.createdAt)) }

    val (processedMinutes, queueSizeByMinute, leftOver) = workWithDesks
      .foldLeft((List[ProcessedBatchOfWork](), List[Double](), BatchOfWork(List()))) {
        case ((processedBatchesSoFar, queueSizeByMinute, spillover), (work, desksOpen)) =>
          val (processedBatch, _) = desksOpen.process(spillover + work)
          val processedBatches = processedBatchesSoFar :+ ProcessedBatchOfWork(work.createdAt, processedBatch)
          val queueSize = processedBatch.outstanding.loads.map(_.load).sum
          (processedBatches, queueSize :: queueSizeByMinute, processedBatch.outstanding)
      }

    ProcessedQueue(sla, work.length, processedMinutes, leftOver, queueSizeByMinute.reverse)
  }

  def processPassengers(sla: Int, passengersByMinute: Iterable[Iterable[Double]]): ProcessedQueue = {
    if (capacity.length != passengersByMinute.size) {
      throw new Exception(s"capacity & work lengths don't match: ${capacity.length} vs ${passengersByMinute.size}")
    }

    val workWithDesks: Iterable[(Iterable[Work], Capacity)] = passengersByMinute.zipWithIndex
      .map { case (passengers, minute) => (minute, passengers.map(paxWorkLoad => Work(paxWorkLoad, minute))) }
      .zip(capacity)
      .map { case ((minute, passengers), d) => (passengers, Capacity(d, minute)) }

    val (processedMinutes, queueSizeByMinute, leftOver) = workWithDesks
      .foldLeft((List[ProcessedBatchOfWork](), List[Double](), BatchOfWork(List()))) {
        case ((processedBatchesSoFar, queueSizeByMinute, spillover), (passengers, desksOpen)) =>
          val (processedBatch, _) = desksOpen.process(spillover + passengers.toList)
          val processedBatches = processedBatchesSoFar :+ ProcessedBatchOfWork(desksOpen.availableAt, processedBatch)
          val paxInQueue = processedBatch.outstanding.loads.size
          (processedBatches, paxInQueue :: queueSizeByMinute, processedBatch.outstanding)
      }

    ProcessedQueue(sla, passengersByMinute.size, processedMinutes, leftOver, queueSizeByMinute.reverse)
  }
}

case class Capacity(value: Double, availableAt: Int) {
  lazy val isEmpty: Boolean = value == 0

  def remove(load: Double): Capacity = this.copy(value = Math.max(value - load, 0))

  def process(work: BatchOfWork): (BatchOfWork, Capacity) = work.loads.foldLeft((BatchOfWork(List()), this)) {
    case ((allProcessedWork, capacityLeft), nextWork) =>
      val (processedWork, newCap) = if (!capacityLeft.isEmpty)
        nextWork.process(capacityLeft)
      else
        (nextWork, capacityLeft)

      (allProcessedWork + processedWork, newCap)
  }
}

case class ProcessedLoad(load: Double, createdAt: Int, processedAt: Int) {
  lazy val waitTime: Int = processedAt - createdAt
  lazy val loadedWait: Double = waitTime * load

  def excessLoadedWait(sla: Int): Double = Math.max(waitTime - sla, 0) * load
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
  val nonEmpty: Boolean = loads.nonEmpty

  lazy val completed: BatchOfWork = BatchOfWork(loads.filter(_.completed))
  lazy val outstanding: BatchOfWork = BatchOfWork(loads.filter(!_.completed))

  def +(loadsToAdd: List[Work]): BatchOfWork = this.copy(loads = (loads ::: loadsToAdd).sortBy(_.createdAt))

  def +(loadToAdd: Work): BatchOfWork = this.copy(loads = (loadToAdd :: loads).sortBy(_.createdAt))
}
