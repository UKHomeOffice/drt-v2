package services

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.util.{Success, Try}

class Timer {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val startMillis: MillisSinceEpoch = nowMillis
  var takenMillis: MillisSinceEpoch = 0L

  private def nowMillis: MillisSinceEpoch = SDate.now().millisSinceEpoch

  def soFarMillis: MillisSinceEpoch = nowMillis - startMillis

  def stop: MillisSinceEpoch = {
    takenMillis = nowMillis - startMillis
    takenMillis
  }

  def report(msg: String): Unit = log.warn(s"${soFarMillis}ms $msg")

  def compare(other: Timer, msg: String): Unit = {
    val otherMillis = other.soFarMillis - soFarMillis

    if (otherMillis > soFarMillis && soFarMillis > 0) log.warn(s"$msg **slower**: $otherMillis > $soFarMillis")
    else log.warn(s"$msg quicker: $otherMillis < $soFarMillis")
  }
}

case class ProcessedWork(util: List[Double],
                         waits: List[Int],
                         residual: IndexedSeq[Double],
                         totalWait: Double,
                         excessWait: Double)

case class Cost(paxPenalty: Double, slaPenalty: Double, staffPenalty: Double, churnPenalty: Int, totalPenalty: Double)

object Optimiser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val weightSla = 10
  val weightChurn = 50
  val weightPax = 0.05
  val weightStaff = 3
  val blockSize = 5
  val targetWidth = 60
  val rollingBuffer = 120

  def crunch(workloads: Seq[Double],
             minDesks: Seq[Int],
             maxDesks: Seq[Int],
             config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    val indexedWork = workloads.toIndexedSeq
    val indexedMinDesks = minDesks.toIndexedSeq

    val fairMaxD = if (workloads.length >= 60) {
      val fairXmax = rollingFairXmax(indexedWork, indexedMinDesks, blockSize, (0.75 * config.sla).round.toInt, targetWidth, rollingBuffer)
      fairXmax.zip(maxDesks).map { case (fair, orig) => List(fair, orig).min }
    } else maxDesks.toIndexedSeq

    val desks = optimiseWin(indexedWork, indexedMinDesks, fairMaxD, config.sla, weightChurn, weightPax, weightStaff, weightSla)
    val waits = processWork(indexedWork, desks, config.sla, IndexedSeq()).waits

    Success(OptimizerCrunchResult(desks.toIndexedSeq, waits))
  }

  def runSimulationOfWork(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Seq[Int] =
    Optimiser.processWork(workloads.toIndexedSeq, desks.toIndexedSeq, config.sla, IndexedSeq()).waits

  def approx(x: IndexedSeq[Int], y: IndexedSeq[Int], i: Seq[Double]): List[Double] = {
    val diffX = x(1) - x.head
    val diffY = y(1) - y.head
    val ratio = diffY.toDouble / diffX
    i.map(_ * ratio).toList
  }

  def leftwardDesks(work: IndexedSeq[Double],
                    xmin: IndexedSeq[Int],
                    xmax: IndexedSeq[Int],
                    blockSize: Int,
                    backlog: Double): IndexedSeq[Int] = {
    val workWithMinMaxDesks: Iterator[(IndexedSeq[Double], (IndexedSeq[Int], IndexedSeq[Int]))] = work.grouped(blockSize).zip(xmin.grouped(blockSize).zip(xmax.grouped(blockSize)))

    workWithMinMaxDesks.foldLeft((List[Int](), backlog)) {
      case ((desks, bl), (workBlock, (xminBlock, xmaxBlock))) =>
        var guess = List(((bl + workBlock.sum) / blockSize).round.toInt, xmaxBlock.head).min

        while (cumulativeSum(workBlock.map(_ - guess)).min < 0 - bl && guess > xminBlock.head) {
          guess = guess - 1
        }

        guess = List(guess, xminBlock.head).max

        val newBacklog = (0 until blockSize).foldLeft(bl) {
          case (accBl, i) =>
            List(accBl + workBlock(i) - guess, 0).max
        }

        (desks ++ List.fill(blockSize)(guess), newBacklog)
    }._1.toIndexedSeq
  }

  def processWork(work: IndexedSeq[Double],
                  capacity: IndexedSeq[Int],
                  sla: Int,
                  qstart: IndexedSeq[Double]): ProcessedWork = {
    var q = qstart
    var totalWait: Double = 0d
    var excessWait: Double = 0d

    val (finalWait, finalUtil) = work.indices.foldLeft((List[Int](), List[Double]())) {
      case ((wait, util), minute) =>
        q = work(minute) +: q
        var resource: Double = capacity(minute)
        var age = q.size

        while (age > 0) {
          val nextWorkToProcess = q(age - 1)
          val surplus = resource - nextWorkToProcess
          if (surplus >= 0) {
            totalWait = totalWait + nextWorkToProcess * (age - 1)
            if (age - 1 >= sla) excessWait = excessWait + nextWorkToProcess * (age - 1)
            q = q.dropRight(1)
            resource = surplus
            age = age - 1
          } else {
            totalWait = totalWait + resource * (age - 1)
            if (age - 1 >= sla) excessWait = excessWait + resource * (age - 1)
            q = q.dropRight(1) :+ (nextWorkToProcess - resource)
            resource = 0
            age = 0
          }
        }

        (q.size :: wait, (1 - (resource / capacity(minute))) :: util)
    }

    val waitReversed = finalWait.reverse
    val utilReversed = finalUtil.reverse

    ProcessedWork(utilReversed, waitReversed, q, totalWait, excessWait)
  }

  def rollingFairXmax(work: IndexedSeq[Double],
                      xmin: IndexedSeq[Int],
                      blockSize: Int,
                      sla: Int,
                      targetWidth: Int,
                      rollingBuffer: Int): IndexedSeq[Int] = {
    val workWithOverrun = work ++ List.fill(targetWidth)(0d)
    val xminWithOverrun = xmin ++ List.fill(targetWidth)(xmin.takeRight(1).head)

    val result = (workWithOverrun.indices by targetWidth).foldLeft(IndexedSeq[Int]()) { case (acc, startSlot) =>
      val winStart: Int = List(startSlot - rollingBuffer, 0).max
      val i = startSlot + targetWidth + rollingBuffer
      val i1 = workWithOverrun.size

      val winStop: Int = List(i, i1).min
      val winWork = workWithOverrun.slice(winStart, winStop)
      val winXmin = xminWithOverrun.slice(winStart, winStop)

      var backlog = 0d

      if (winStart == 0) backlog = 0

      val guessMax: Int = runningAverage(workWithOverrun, List(blockSize, sla).min).max.ceil.toInt

      val lowerLimit = List(winXmin.max, (winWork.sum / winWork.size).ceil.toInt).max

      var winXmax = guessMax
      var hasExcessWait = false
      var lowerLimitReached = false

      if (guessMax <= lowerLimit)
        winXmax = lowerLimit
      else {
        do {
          val trialDesks = leftwardDesks(winWork.toIndexedSeq, winXmin.toIndexedSeq, IndexedSeq.fill(winXmin.size)(winXmax), blockSize, backlog)
          val trialProcess = processWork(winWork.toIndexedSeq, trialDesks.toIndexedSeq, sla, IndexedSeq(0))
          if (trialProcess.excessWait > 0) {
            winXmax = List(winXmax + 1, guessMax).min
            hasExcessWait = true
          }
          if (winXmax <= lowerLimit) lowerLimitReached = true
          if (!lowerLimitReached && !hasExcessWait) winXmax = winXmax - 1
        } while (!lowerLimitReached && !hasExcessWait)
      }

      val newXmax = acc ++ List.fill(targetWidth)(winXmax)
      0 until targetWidth foreach (j => backlog = List(backlog + winWork(j) - newXmax(winStart), 0).max)
      newXmax
    }.take(work.size)

    result
  }

  def runningAverage(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    val averages = values
      .sliding(windowLength)
      .map(_.map(_ / windowLength).sum).toList

    List.fill(windowLength - 1)(averages.head) ::: averages
  }

  def cumulativeSum(values: Iterable[Double]): Iterable[Double] = values
    .foldLeft(List[Double]()) {
      case (Nil, element) => List(element)
      case (head :: tail, element) => element + head :: head :: tail
    }.reverse

  def blockMean(values: Iterable[Double], blockWidth: Int): Iterable[Double] = values
    .grouped(blockWidth)
    .flatMap(nos => List.fill(blockWidth)(nos.sum / blockWidth))
    .toIterable

  def blockMax(values: Iterable[Double], blockWidth: Int): Iterable[Double] = values
    .grouped(blockWidth)
    .flatMap(nos => List.fill(blockWidth)(nos.max))
    .toIterable

  def seqR(from: Int, by: Int, length: Int): IndexedSeq[Int] = 0 to length map (i => (i + from) * by)

  def churn(churnStart: Int, capacity: IndexedSeq[Int]): Int = capacity.zip(churnStart +: capacity)
    .collect { case (x, xLag) => x - xLag }
    .filter(_ > 0)
    .sum

  def cost(work: IndexedSeq[Double],
           sla: Int,
           weightChurn: Double,
           weightPax: Double,
           weightStaff: Double,
           weightSla: Double,
           qStart: IndexedSeq[Double],
           churnStart: Int)(capacity: IndexedSeq[Int]): Cost = {
    var simRes = processWork(work, capacity, sla, qStart)

    var finalCapacity = capacity.takeRight(1).head
    val backlog = simRes.residual.reverse
    val totalBacklog = backlog.sum

    if (backlog.nonEmpty) {
      finalCapacity = List(finalCapacity, 1).max
      val cumBacklog = cumulativeSum(backlog)
      val cumCapacity = seqR(0, finalCapacity, (totalBacklog.toDouble / finalCapacity).ceil.toInt)
      val overrunSlots = cumCapacity.indices
      val backlogBoundaries = approx(cumCapacity, overrunSlots, cumBacklog.toList)
      val startSlots = 0d :: backlogBoundaries.dropRight(1).map(_.floor)
      val endSlots = backlogBoundaries.map(_.floor)
      val alreadyWaited = (1 to backlog.length).reverse
      val meanWaits = startSlots
        .zip(endSlots)
        .map { case (x, y) => (x.toDouble + y) / 2 }
        .zip(alreadyWaited)
        .map { case (x, y) => x + y }

      val excessFilter = meanWaits.map(_ > sla)
      val newTotalWait = simRes.totalWait + backlog.zip(meanWaits).map { case (x, y) => x * y }.sum
      val newExcessWait = simRes.excessWait + excessFilter
        .zip(backlog.zip(meanWaits))
        .map {
          case (true, (x, y)) => x * y
          case _ => 0
        }.sum

      simRes = simRes.copy(totalWait = newTotalWait, excessWait = newExcessWait)
    }

    val paxPenalty = simRes.totalWait
    val slaPenalty = simRes.excessWait
    val staffPenalty = simRes.util.zip(capacity).map { case (u, c) => (1 - u) * c.toDouble }.sum
    val churnPenalty = churn(churnStart, capacity :+ finalCapacity)

    val totalPenalty = (weightPax * paxPenalty) +
      (weightStaff * staffPenalty.toDouble) +
      (weightChurn * churnPenalty.toDouble) +
      (weightSla * slaPenalty.toDouble)

    Cost(paxPenalty.toInt, slaPenalty.toInt, staffPenalty, churnPenalty, totalPenalty)
  }

  def neighbouringPoints(x0: Int, xmin: Int, xmax: Int): IndexedSeq[Int] = (xmin to xmax)
    .filterNot(_ == x0)
    .sortBy(x => (x - x0).abs)

  def branchBound(startingX: IndexedSeq[Int],
                  cost: IndexedSeq[Int] => Cost,
                  xmin: IndexedSeq[Int],
                  xmax: IndexedSeq[Int],
                  concavityLimit: Int): Iterable[Int] = {
    val x = mutable.IndexedSeq() ++ startingX
    var incumbent = startingX
    val minutes = x.length
    var bestSoFar = cost(incumbent.toIndexedSeq).totalPenalty
    val candidates: mutable.IndexedSeq[IndexedSeq[Int]] = mutable.IndexedSeq() ++ (0 until minutes).map(i => neighbouringPoints(startingX(i), xmin(i), xmax(i)))

    var cursor = minutes - 1

    while (cursor >= 0) {
      while (candidates(cursor).nonEmpty) {
        x(cursor) = candidates(cursor).take(1).head
        candidates(cursor) = candidates(cursor).drop(1)

        val trialPenalty = cost(x.toIndexedSeq).totalPenalty

        if (trialPenalty > bestSoFar + concavityLimit) {
          if (x(cursor) > incumbent(cursor)) {
            candidates(cursor) = candidates(cursor).filter(_ < x(cursor))
          } else {
            candidates(cursor) = candidates(cursor).filter(_ > x(cursor))
          }
        } else {
          if (trialPenalty < bestSoFar) {
            incumbent = x.toIndexedSeq
            bestSoFar = trialPenalty
          }
          if (cursor < minutes - 1) cursor = cursor + 1
        }
      }
      candidates(cursor) = neighbouringPoints(incumbent(cursor), xmin(cursor), xmax(cursor))
      x(cursor) = incumbent(cursor)
      cursor = cursor - 1
    }
    x
  }

  def optimiseWin(work: IndexedSeq[Double],
                  minDesks: IndexedSeq[Int],
                  maxDesks: IndexedSeq[Int],
                  sla: Int,
                  weightChurn: Double,
                  weightPax: Double,
                  weightStaff: Double,
                  weightSla: Double): IndexedSeq[Int] = {
    val blockWidth = 15
    val concavityLimit = 30
    val winStep = 60
    val smoothingWidth = blockWidth
    val winWidth = List(90, work.length).min

    var winStart = 0
    var winStop = winWidth
    var qStart = IndexedSeq(0d)
    var churnStart = 0

    val desks: mutable.IndexedSeq[Int] = mutable.IndexedSeq[Int]() ++ blockMean(runningAverage(work, smoothingWidth), blockWidth)
      .map(_.ceil.toInt)
      .zip(maxDesks)
      .map {
        case (d, max) => List(d, max).min
      }
      .zip(minDesks)
      .map {
        case (d, min) => List(d, min).max
      }

    def myCost(costWork: IndexedSeq[Double], costQStart: IndexedSeq[Double], costChurnStart: Int)
              (capacity: IndexedSeq[Int]): Cost =
      cost(costWork, sla, weightChurn, weightPax, weightStaff, weightSla, costQStart, costChurnStart)(capacity.flatMap(c => IndexedSeq.fill(blockWidth)(c)))

    var shouldStop = false

    do {
      val currentWork = work.slice(winStart, winStop)
      val blockGuess = desks.slice(winStart, winStop).grouped(blockWidth).map(_.head).toIndexedSeq
      val xminCondensed = minDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head).toIndexedSeq
      val xmaxCondensed = maxDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head).toIndexedSeq

      val windowIndices = winStart until winStop
      branchBound(blockGuess, myCost(currentWork, qStart, churnStart), xminCondensed, xmaxCondensed, concavityLimit)
        .flatMap(o => List.fill(blockWidth)(o))
        .zip(windowIndices)
        .foreach {
          case (d, i) => desks(i) = d
        }

      shouldStop = winStop == work.length

      if (!shouldStop) {
        val stop = winStart + winStep
        val workToProcess = work.slice(winStart, stop)
        val desksToProcess = desks.slice(winStart, stop)
        qStart = processWork(workToProcess.toIndexedSeq, desksToProcess.toIndexedSeq, sla, qStart.toIndexedSeq).residual
        churnStart = desks(stop)
        winStart = winStart + winStep
        winStop = List(winStop + winStep, work.length).min
      }
    } while (!shouldStop)

    desks
  }
}
