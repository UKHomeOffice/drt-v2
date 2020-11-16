package services

import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}


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

  def crunch(workloads: Iterable[Double],
             minDesks: Iterable[Int],
             maxDesks: Iterable[Int],
             config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    val indexedWork = workloads.toIndexedSeq
    val indexedMinDesks = minDesks.toIndexedSeq

    val bestMaxDesks = if (workloads.size >= 60) {
      val fairMaxDesks = rollingFairXmax(indexedWork, indexedMinDesks, blockSize, (0.75 * config.sla).round.toInt, targetWidth, rollingBuffer)
      fairMaxDesks.zip(maxDesks).map { case (fair, orig) => List(fair, orig).min }
    } else maxDesks.toIndexedSeq

    if (bestMaxDesks.exists(_ < 0)) log.warn(s"Max desks contains some negative numbers")

    for {
      desks <- tryOptimiseWin(indexedWork, indexedMinDesks, bestMaxDesks, config.sla, weightChurn, weightPax, weightStaff, weightSla)
      processedWork <- tryProcessWork(indexedWork, desks, config.sla, IndexedSeq())
    } yield OptimizerCrunchResult(desks.toIndexedSeq, processedWork.waits)
  }

  def runSimulationOfWork(workloads: Iterable[Double], desks: Iterable[Int], config: OptimizerConfig): Try[Seq[Int]] =
    Optimiser.tryProcessWork(workloads.toIndexedSeq, desks.toIndexedSeq, config.sla, IndexedSeq()).map(_.waits)

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

  def tryProcessWork(work: IndexedSeq[Double],
                     capacity: IndexedSeq[Int],
                     sla: Int,
                     qstart: IndexedSeq[Double]): Try[ProcessedWork] = {

    if (capacity.length != work.length) {
      Failure(new Exception(s"capacity & work don't match: ${capacity.length} vs ${work.length}"))
    } else Try {
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
  }

  def rollingFairXmax(work: IndexedSeq[Double],
                      xmin: IndexedSeq[Int],
                      blockSize: Int,
                      sla: Int,
                      targetWidth: Int,
                      rollingBuffer: Int): IndexedSeq[Int] = {
    val workWithOverrun = work ++ List.fill(targetWidth)(0d)
    val xminWithOverrun = xmin ++ List.fill(targetWidth)(xmin.takeRight(1).head)

    var backlog = 0d

    val result = (workWithOverrun.indices by targetWidth).foldLeft(IndexedSeq[Int]()) { case (acc, startSlot) =>
      val winStart: Int = List(startSlot - rollingBuffer, 0).max
      val i = startSlot + targetWidth + rollingBuffer
      val i1 = workWithOverrun.size
      val winStop: Int = List(i, i1).min
      val winWork = workWithOverrun.slice(winStart, winStop)
      val winXmin = xminWithOverrun.slice(winStart, winStop)

      if (winStart == 0) backlog = 0

      val runAv = runningAverage(winWork, List(blockSize, sla).min)
      val guessMax: Int = runAv.max.ceil.toInt

      val lowerLimit = List(winXmin.max, (winWork.sum / winWork.size).ceil.toInt).max

      var winXmax = guessMax
      var hasExcessWait = false
      var lowerLimitReached = false

      if (guessMax <= lowerLimit)
        winXmax = lowerLimit
      else {
        do {
          val trialDesks = leftwardDesks(winWork, winXmin, IndexedSeq.fill(winXmin.size)(winXmax), blockSize, backlog)
          val trialProcessExcessWait = tryProcessWork(winWork, trialDesks, sla, IndexedSeq(0)) match {
            case Success(pw) => pw.excessWait
            case Failure(t) => throw t
          }
          if (trialProcessExcessWait > 0) {
            winXmax = List(winXmax + 1, guessMax).min
            hasExcessWait = true
          }
          if (winXmax <= lowerLimit) lowerLimitReached = true
          if (!lowerLimitReached && !hasExcessWait) winXmax = winXmax - 1
        } while (!lowerLimitReached && !hasExcessWait)
      }

      val newXmax = acc ++ List.fill(targetWidth)(winXmax)
      0 until targetWidth foreach { j =>
        backlog = List(backlog + winWork(j) - newXmax(winStart), 0).max
      }
      newXmax
    }.take(work.size)

    result
  }

  def runningAverage(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    val averages = values
      .sliding(windowLength)
      .map(_.sum / windowLength).toList

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
    var simRes = tryProcessWork(work, capacity, sla, qStart) match {
      case Success(pw) => pw
      case Failure(t) => throw t
    }

    var finalCapacity = capacity.takeRight(1).head
    val backlog = simRes.residual.reverse
    val totalBacklog = backlog.sum

    if (backlog.nonEmpty) {
      finalCapacity = List(finalCapacity, 1).max
      val cumBacklog = cumulativeSum(backlog)
      val cumCapacity = seqR(0, finalCapacity, (totalBacklog / finalCapacity).ceil.toInt)
      val overrunSlots = cumCapacity.indices
      val backlogBoundaries = approx(cumCapacity, overrunSlots, cumBacklog.toList)
      val startSlots = 0d :: backlogBoundaries.dropRight(1).map(_.floor)
      val endSlots = backlogBoundaries.map(_.floor)
      val alreadyWaited = (1 to backlog.length).reverse
      val meanWaits = startSlots
        .zip(endSlots)
        .map { case (x, y) => (x + y) / 2 }
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
//    .sorted.reverse
    .sortBy(x => (x - x0).abs)

  def branchBound(startingX: IndexedSeq[Int],
                  cost: IndexedSeq[Int] => Cost,
                  xmin: IndexedSeq[Int],
                  xmax: IndexedSeq[Int],
                  concavityLimit: Int): Iterable[Int] = {
    val desks = startingX.to[mutable.IndexedSeq]
    var incumbent = startingX
    val minutes = desks.length
    var bestSoFar = cost(incumbent.toIndexedSeq).totalPenalty
    val candidates = (0 until minutes)
      .map(i => neighbouringPoints(startingX(i), xmin(i), xmax(i)))
      .to[mutable.IndexedSeq]

    var cursor = minutes - 1

    while (cursor >= 0) {
      while (candidates(cursor).nonEmpty) {
        desks(cursor) = candidates(cursor).head
        candidates(cursor) = candidates(cursor).drop(1)

        val trialPenalty = cost(desks.toIndexedSeq).totalPenalty

        if (trialPenalty > bestSoFar + concavityLimit) {
          if (desks(cursor) > incumbent(cursor)) {
            candidates(cursor) = candidates(cursor).filter(_ < desks(cursor))
          } else {
            candidates(cursor) = candidates(cursor).filter(_ > desks(cursor))
          }
        } else {
          if (trialPenalty < bestSoFar) {
            incumbent = desks.toIndexedSeq
            bestSoFar = trialPenalty
          }
          if (cursor < minutes - 1) cursor = cursor + 1
        }
      }
      candidates(cursor) = neighbouringPoints(incumbent(cursor), xmin(cursor), xmax(cursor))
      desks(cursor) = incumbent(cursor)
      cursor = cursor - 1
    }
    desks
  }

  def branchBoundBinarySearch(startingX: IndexedSeq[Int],
                              cost: IndexedSeq[Int] => Cost,
                              xmin: IndexedSeq[Int],
                              xmax: IndexedSeq[Int],
                              concavityLimit: Int): Iterable[Int] = {
    val desks = startingX.to[mutable.IndexedSeq]
    var incumbent = startingX
    val minutes = desks.length
    var bestSoFar = cost(incumbent.toIndexedSeq).totalPenalty
    val candidates = (0 until minutes)
      .map(i => neighbouringPoints(startingX(i), xmin(i), xmax(i)))
      .to[mutable.IndexedSeq]

    var cursor = minutes - 1

    while (cursor >= 0) {
      while (candidates(cursor).nonEmpty) {
        val middle = ((candidates(cursor).length - 1) / 2).floor.toInt
        desks(cursor) = candidates(cursor)(middle)
        candidates(cursor) = candidates(cursor).filterNot(_ == desks(cursor))

        val trialPenalty = cost(desks.toIndexedSeq).totalPenalty

        val isBetter = trialPenalty <= bestSoFar + concavityLimit

        if (isBetter) {
          if (trialPenalty < bestSoFar) {
            incumbent = desks.toIndexedSeq
            bestSoFar = trialPenalty
          }
          if (cursor < minutes - 1) cursor = cursor + 1
        } else {
          if (desks(cursor) > incumbent(cursor)) {
            candidates(cursor) = candidates(cursor).filter(_ < desks(cursor))
          } else {
            candidates(cursor) = candidates(cursor).filter(_ > desks(cursor))
          }
        }
      }
      candidates(cursor) = neighbouringPoints(incumbent(cursor), xmin(cursor), xmax(cursor))
      desks(cursor) = incumbent(cursor)
      cursor = cursor - 1
    }
    desks
  }

  def tryOptimiseWin(work: IndexedSeq[Double],
                     minDesks: IndexedSeq[Int],
                     maxDesks: IndexedSeq[Int],
                     sla: Int,
                     weightChurn: Double,
                     weightPax: Double,
                     weightStaff: Double,
                     weightSla: Double): Try[IndexedSeq[Int]] = {
    if (work.length != minDesks.length) {
      Failure(new Exception(s"work & minDesks are not equal length: ${work.length} vs ${minDesks.length}"))
    } else if (work.length != maxDesks.length) {
      Failure(new Exception(s"work & maxDesks are not equal length: ${work.length} vs ${maxDesks.length}"))
    } else Try {
      val blockWidth = 15
      val concavityLimit = 30
      val winStep = 60
      val smoothingWidth = blockWidth
      val winWidth = List(90, work.length).min

      var winStart = 0
      var winStop = winWidth
      var qStart = IndexedSeq(0d)
      var churnStart = 0

      val desks = blockMean(runningAverage(work, smoothingWidth), blockWidth)
        .map(_.ceil.toInt)
        .zip(maxDesks)
        .map {
          case (d, max) => List(d, max).min
        }
        .zip(minDesks)
        .map {
          case (d, min) => List(d, min).max
        }.to[mutable.IndexedSeq]

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
//        branchBoundBinarySearch(blockGuess, myCost(currentWork, qStart, churnStart), xminCondensed, xmaxCondensed, concavityLimit)
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
          qStart = tryProcessWork(workToProcess.toIndexedSeq, desksToProcess.toIndexedSeq, sla, qStart.toIndexedSeq) match {
            case Success(pw) => pw.residual
            case Failure(t) => throw t
          }
          churnStart = desks(stop)
          winStart = winStart + winStep
          winStop = List(winStop + winStep, work.length).min
        }
      } while (!shouldStop)

      desks
    }
  }
}
