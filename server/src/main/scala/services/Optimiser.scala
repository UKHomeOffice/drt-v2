package services

import drt.shared.CrunchApi.MillisSinceEpoch
import javax.script.{ScriptEngine, ScriptEngineManager}
import org.renjin.sexp.DoubleVector
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.{immutable, mutable}
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

object Optimiser {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val manager: ScriptEngineManager = new ScriptEngineManager()
  val engine: ScriptEngine = manager.getEngineByName("Renjin")
  var checkAllAgainstR = false
  var checkOptimiserAgainstR = false

//  def loadOptimiserScript: AnyRef = {
//    if (engine == null) throw new scala.RuntimeException("Couldn't load Renjin script engine on the classpath")
//    val asStream: InputStream = getClass.getResourceAsStream("/optimisation-v6.R")
//
//    val optimiserScript = scala.io.Source.fromInputStream(asStream)
//    engine.eval(optimiserScript.bufferedReader())
//  }

//  loadOptimiserScript

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
    log.info(s"Starting optimisation for ${workloads.length} minutes of workload")
    val indexedWorkload = workloads.toIndexedSeq
    val fairMaxD = if (workloads.length >= 60) {
//      val stimer = new Timer
      val fairXmax = rollingFairXmax(indexedWorkload, minDesks.toIndexedSeq, blockSize, config.sla, targetWidth, rollingBuffer)
//      val rtimer = new Timer
//      rollingFairXmaxR(workloads.toList, minDesks.toList, config.sla)
//      rtimer.compare(stimer, "fair xmax")
      fairXmax.zip(maxDesks).map { case (fair, orig) => List(fair, orig).min }
    } else maxDesks.toIndexedSeq
    val stimer = new Timer
//    println(s"fair xmax: $fairMaxD")
    val desks = optimiseWin(indexedWorkload, minDesks.toIndexedSeq, fairMaxD, config.sla, weightChurn, weightPax, weightStaff, weightSla)
    if (stimer.soFarMillis > 2000) log.warn(s"${stimer.soFarMillis}ms slow optimising workload (sla ${config.sla}) ${workloads.mkString(",")}")
//    val rtimer = new Timer
//    optimiseWinR(workloads.toList, minDesks.toList, fairMaxD, config.sla, weightChurn, weightPax, weightStaff, weightSla)
//    rtimer.compare(stimer, "optimise win")
//    val stimer2 = new Timer
    val waits = processWork(indexedWorkload, desks.toIndexedSeq, config.sla, List()).waits
//    val rtimer2 = new Timer
//    processWorkR(workloads.toList, desks.toList, config.sla, List()).waits
//    rtimer2.compare(stimer2, "process work")

    Success(OptimizerCrunchResult(desks.toIndexedSeq, waits))
  }

  def runSimulationOfWork(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Seq[Int] =
    Optimiser.processWork(workloads.toIndexedSeq, desks.toIndexedSeq, config.sla, List()).waits

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

    val result = workWithMinMaxDesks.foldLeft((IndexedSeq[Int](), backlog)) {
      case ((desks, bl), (workBlock, (xminBlock, xmaxBlock))) =>
        var guess = List(((bl + workBlock.sum) / blockSize).round.toInt, xmaxBlock.head).min

        val xminBlockHead = xminBlock.head

        while (workBlock.head - guess < 0 - bl && guess > xminBlockHead) {
          guess = guess - 1
        }

        guess = if (guess > xminBlockHead) guess else xminBlockHead

        val indexedWorkBlock = workBlock.toIndexedSeq

        val newBacklog = (0 until blockSize).foldLeft(bl) {
          case (accBl, i) =>
            val x = accBl + indexedWorkBlock(i) - guess
            if (x > 0) x else 0
        }

        (desks ++ List.fill(blockSize)(guess), newBacklog)
    }._1

    result
  }

//  def leftwardDesksR(work: List[Double],
//                     xmin: List[Int],
//                     xmax: List[Int],
//                     blockSize: Int,
//                     backlog: Double): List[Int] = {
//    engine.put("work", work.toArray)
//    engine.put("xmin", xmin.toArray)
//    engine.put("xmax", xmax.toArray)
//    engine.put("blockSize", blockSize)
//    engine.put("backlog", backlog)
//    engine.eval("result <- leftward.desks(work, xmin, xmax, blockSize, backlog)$desks")
//    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList.map(_.toInt)
//  }

  case class ProcessedWork(util: List[Double],
                           waits: List[Int],
                           residual: List[Double],
                           totalWait: Double,
                           excessWait: Double)

  def processWork(work: IndexedSeq[Double], capacity: IndexedSeq[Int], sla: Int, qstart: List[Double]): ProcessedWork = {
    var q: mutable.IndexedSeq[Double] = mutable.IndexedSeq[Double]() ++ qstart
    var totalWait: Double = 0d
    var excessWait: Double = 0d

    val (finalWait, finalUtil) = work.indices.foldLeft((List[Int](), List[Double]())) {
      case ((wait, util), minute) =>
        q = work(minute) +: q
        var resource: Double = capacity(minute)
        var age = q.size

        while (age > 0) {
          val minuteProcessing = age - 1
          val nextWorkToProcess = q(minuteProcessing)
          val surplus = resource - nextWorkToProcess
          if (surplus >= 0) {
            val waitToAdd = nextWorkToProcess * minuteProcessing
            totalWait = totalWait + waitToAdd
            if (minuteProcessing >= sla) excessWait = excessWait + waitToAdd
            q = q.dropRight(1)
            resource = surplus
            age = minuteProcessing
          } else {
            val waitToAdd = resource * minuteProcessing
            totalWait = totalWait + waitToAdd
            if (minuteProcessing >= sla) excessWait = excessWait + waitToAdd
            q(minuteProcessing) = nextWorkToProcess - resource
            resource = 0
            age = 0
          }
        }

        (q.size :: wait, (1 - (resource / capacity(minute))) :: util)
    }

    val waitReversed = finalWait.reverse
    val utilReversed = finalUtil.reverse

    ProcessedWork(utilReversed, waitReversed, q.toList, totalWait, excessWait)
  }

//  def processWorkR(work: List[Double], capacity: List[Int], sla: Int, qstart: List[Double]): ProcessedWork = {
//    engine.put("work", work.toArray)
//    engine.put("capacity", capacity.toArray)
//    engine.put("sla", sla)
//    engine.put("qstart", qstart.toArray)
//    engine.eval("result <- process.work(work, capacity, sla, qstart)")
//    val waits = engine.eval("result$wait").asInstanceOf[IntVector].toIntArray.toList
//    val excessWait = engine.eval("result$excess.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
//    val totalWait = engine.eval("result$total.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
//    val util: List[Double] = engine.eval("result$util").asInstanceOf[DoubleVector].toDoubleArray.toList
//    val q = engine.eval("result$residual").asInstanceOf[DoubleVector].toDoubleArray.toList
//
//    ProcessedWork(util, waits, q, totalWait, excessWait)
//  }

  def rollingFairXmax(work: IndexedSeq[Double],
                      xmin: IndexedSeq[Int],
                      blockSize: Int,
                      sla: Int,
                      targetWidth: Int,
                      rollingBuffer: Int): IndexedSeq[Int] = {
    val workWithOverrun = work ++ IndexedSeq.fill(targetWidth)(0d)
    val xminWithOverrun = xmin ++ IndexedSeq.fill(targetWidth)(xmin.takeRight(1).head)

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
          val trialDesks = leftwardDesks(winWork, winXmin, IndexedSeq.fill(winXmin.size)(winXmax), blockSize, backlog)
          val trialProcess = processWork(winWork, trialDesks, sla, List(0))
          if (trialProcess.excessWait > 0) {
            winXmax = List(winXmax + 1, guessMax).min
            hasExcessWait = true
          }
          if (winXmax <= lowerLimit)
            lowerLimitReached = true

          if (!lowerLimitReached && !hasExcessWait)
            winXmax = winXmax - 1
        } while (!lowerLimitReached && !hasExcessWait)
      }

      val newXmax = acc ++: IndexedSeq.fill(targetWidth)(winXmax)
      0 until targetWidth foreach (j => backlog = List(backlog + winWork(j) - newXmax(winStart), 0).max)
      newXmax
    }.take(work.size)

    result.toIndexedSeq
  }

//  def rollingFairXmaxR(work: IndexedSeq[Double], xmin: IndexedSeq[Int], sla: Int): List[Int] = {
//    engine.put("w", work.toArray)
//    engine.put("xmin", xmin.toArray)
//    engine.put("adjustedSla", sla)
//    engine.eval("rolling.fair.xmax(w, xmin=xmin, block.size=5, sla=adjustedSla, target.width=60, rolling.buffer=120)").asInstanceOf[DoubleArrayVector].toIntArray.toList
//  }

  def runningAverage(values: Iterable[Double], windowLength: Int): IndexedSeq[Double] = {
    val averages = values
      .sliding(windowLength)
      .map(_.map(_ / windowLength).sum).toList

    (List.fill(windowLength - 1)(averages.head) ::: averages).toIndexedSeq
  }

  def runningAverageR(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("windowLength", windowLength)
    engine.eval("result <- running.avg(values, windowLength)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def cumulativeSum(values: Iterable[Double]): Iterable[Double] = values.scanLeft(0d)(_ + _).drop(1)

  def cumulativeSumR(values: Iterable[Double]): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.eval("result <- cumsum(values)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def blockMean(values: IndexedSeq[Double], blockWidth: Int): IndexedSeq[Double] = values
    .grouped(blockWidth).toIndexedSeq
    .flatMap(nos => List.fill(blockWidth)(nos.sum / blockWidth))

  def blockMeanR(values: Iterable[Double], blockWidth: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("blockWidth", blockWidth)
    engine.eval("result <- block.mean(values, blockWidth)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toIterable
  }

  def blockMax(values: Iterable[Double], blockWidth: Int): Iterable[Double] = values
    .grouped(blockWidth)
    .flatMap(nos => List.fill(blockWidth)(nos.max))
    .toIterable

  def seqR(from: Int, by: Int, length: Int): IndexedSeq[Int] = (0 to length map (i => (i + from) * by))

  def churn(churnStart: Int, capacity: List[Int]): Int = capacity.zip(churnStart :: capacity)
    .collect { case (x, xlag) => x - xlag }
    .filter(_ > 0)
    .sum

//  def churnR(churnStart: Int, capacity: List[Int]): Int = {
//    engine.put("churnStart", churnStart)
//    engine.put("capacity", capacity.toArray)
//    engine.eval("result <- churn(churnStart, capacity)")
//    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
//  }

  case class Cost(paxPenalty: Double, slaPenalty: Double, staffPenalty: Double, churnPenalty: Int, totalPenalty: Double)

  def cost(work: IndexedSeq[Double],
           sla: Int,
           weightChurn: Double,
           weightPax: Double,
           weightStaff: Double,
           weightSla: Double,
           qStart: List[Double],
           churnStart: Int)(capacity: IndexedSeq[Int]): Cost = {
    var simres = processWork(work, capacity, sla, qStart)

    var finalCapacity = capacity.takeRight(1).head
    val backlog = simres.residual.reverse
    val totalBacklog = backlog.sum

    if (backlog.nonEmpty) {
      finalCapacity = List(finalCapacity, 1).max
      val cumBacklog = cumulativeSum(backlog)
      val cumCapacity = seqR(0, finalCapacity, (totalBacklog.toDouble / finalCapacity).ceil.toInt)
      val overrunSlots = cumCapacity.indices
      val backlogBoundaries = approx(cumCapacity, overrunSlots, cumBacklog.toList)
      val startSlots: immutable.Seq[Int] = 0 :: backlogBoundaries.dropRight(1).map(_.floor.toInt)
      val endSlots: immutable.Seq[Int] = backlogBoundaries.map(_.floor.toInt)
      val alreadyWaited = (1 to backlog.length).reverse
      val meanWaits = startSlots
        .zip(endSlots)
        .map { case (x, y) => (x.toDouble + y) / 2 }
        .zip(alreadyWaited)
        .map { case (x, y) => x + y }

      val excessFilter: Seq[Boolean] = meanWaits.map(_ > sla)
      val newTotalWait = simres.totalWait + backlog.zip(meanWaits).map { case (x, y) => x * y }.sum
      val newExcessWait = simres.excessWait + excessFilter
        .zip(backlog.zip(meanWaits))
        .collect {
          case (true, (x, y)) => x * y
        }.sum

      simres = simres.copy(totalWait = newTotalWait, excessWait = newExcessWait)
    }

    val paxPenalty = simres.totalWait
    val slaPenalty = simres.excessWait
    val staffPenalty = simres.util.zip(capacity).map { case (u, c) => (1 - u) * c.toDouble }.sum
    val churnPenalty = churn(churnStart, capacity.toList :+ finalCapacity)

    val totalPenalty = (weightPax * paxPenalty) +
      (weightStaff * staffPenalty.toDouble) +
      (weightChurn * churnPenalty.toDouble) +
      (weightSla * slaPenalty.toDouble)

    Cost(paxPenalty.toInt, slaPenalty.toInt, staffPenalty, churnPenalty, totalPenalty)
  }

//  def costR(work: List[Double],
//            sla: Int,
//            weightChurn: Double,
//            weightPax: Double,
//            weightStaff: Double,
//            weightSla: Double,
//            qStart: List[Double],
//            churnStart: Int)(capacity: List[Int]): Cost = {
//    engine.put("work", work.toArray)
//    engine.put("sla", sla)
//    engine.put("weightChurn", weightChurn.toInt)
//    engine.put("weightPax", weightPax)
//    engine.put("weightStaff", weightStaff.toInt)
//    engine.put("weightSla", weightSla.toInt)
//    engine.put("qStart", qStart.toArray)
//    engine.put("churnStart", churnStart)
//    engine.put("capacity", capacity.toArray)
//    engine.eval("result <- cost(work, capacity, sla, weightPax, weightStaff, weightChurn, weightSla, qstart = qStart, churn.start = churnStart)")
//    val rpax = engine.eval("result$pax").asInstanceOf[DoubleVector].toIntArray.head
//    val rsla = engine.eval("result$sla.p").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
//    val rstaff = engine.eval("result$staff").asInstanceOf[DoubleVector].toDoubleArray.head
//    val rchurn = engine.eval("result$churn").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
//    val rtotal = engine.eval("result$total").asInstanceOf[DoubleVector].toDoubleArray.head
//    Cost(rpax, rsla, rstaff, rchurn, rtotal)
//  }

  def neighbouringPoints(x0: Int, xmin: Int, xmax: Int): IndexedSeq[Int] = (xmin to xmax)
    .filterNot(_ == x0)
    .sortBy(x => (x - x0).abs)

  def branchBound(startingX: IndexedSeq[Int],
                  cost: IndexedSeq[Int] => Cost,
                  xmin: IndexedSeq[Int],
                  xmax: IndexedSeq[Int],
                  concavityLimit: Int): Iterable[Int] = {
    val x: mutable.IndexedSeq[Int] = mutable.IndexedSeq() ++ startingX
    var incumbent = startingX
    val minutes = x.length
    var bestSoFar = cost(incumbent).totalPenalty
    val candidates: mutable.IndexedSeq[Seq[Int]] = mutable.IndexedSeq() ++ (0 until minutes).map(i => neighbouringPoints(startingX(i), xmin(i), xmax(i)))

    var cursor = minutes - 1

    while (cursor >= 0) {
      while (candidates(cursor).nonEmpty) {
        x(cursor) = candidates(cursor).take(1).head
        candidates(cursor) = candidates(cursor).drop(1)

        val trialPenalty = cost(x).totalPenalty

        if (trialPenalty > bestSoFar + concavityLimit) {
          if (x(cursor) > incumbent(cursor)) {
            candidates(cursor) = candidates(cursor).filter(_ < x(cursor))
          } else {
            candidates(cursor) = candidates(cursor).filter(_ > x(cursor))
          }
        } else {
          if (trialPenalty < bestSoFar) {
            incumbent = x
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
    val timer = new Timer

    val blockWidth = 15
    val concavityLimit = 30
    val winStep = 60
    val smoothingWidth = blockWidth
    val winWidth = List(90, work.length).min

    var winStart = 0
    var winStop = winWidth
    var qStart = List(0d)
    var churnStart = 0

    val desks: mutable.IndexedSeq[Int] = mutable.IndexedSeq[Int]() ++ blockMean(runningAverage(work, smoothingWidth), blockWidth)
      .map(_.ceil.toInt)
//      .toList
      .zip(maxDesks)
      .map {
        case (d, max) => List(d, max).min
      }
      .zip(minDesks)
      .map {
        case (d, min) => List(d, min).max
      }

    def myCost(costWork: IndexedSeq[Double], costQStart: List[Double], costChurnStart: Int)(capacity: IndexedSeq[Int]): Cost =
      cost(costWork, sla, weightChurn, weightPax, weightStaff, weightSla, costQStart, costChurnStart)(capacity.flatMap(c => List.fill(blockWidth)(c)))

    var shouldStop = false

    do {
      val currentWork = work.slice(winStart, winStop)
      val desksSlice: IndexedSeq[Int] = desks.slice(winStart, winStop).toIndexedSeq
      val desksSliceGrouped: IndexedSeq[IndexedSeq[Int]] = desksSlice.grouped(blockWidth).toIndexedSeq
      val blockGuess = desksSliceGrouped.map(_.head)
      val xminCondensed = minDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head)
      val xmaxCondensed = maxDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head)

      val windowIndices = winStart until winStop
      branchBound(blockGuess, myCost(currentWork, qStart, churnStart), xminCondensed.toIndexedSeq, xmaxCondensed.toIndexedSeq, concavityLimit)
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
        qStart = processWork(workToProcess, desksToProcess, sla, qStart).residual
        churnStart = desks(stop)
        winStart = winStart + winStep
        winStop = List(winStop + winStep, work.length).min
      }
    } while (!shouldStop)

    val result = desks.toIndexedSeq

    if (checkOptimiserAgainstR) {
      val rtimer = new Timer
      val rResult = optimiseWinR(work, minDesks, maxDesks, sla, weightChurn, weightPax, weightStaff, weightSla)
      rtimer.compare(timer, "optimise win")

      result
        .zip(rResult)
        .grouped(15)
        .map(_.head)
        .zipWithIndex
        .foreach {
          case ((s, r), i) if s != r => log.warn(s"optimiser comparison - ${i * 15}: scala $s vs r $r")
          case _ =>
        }
    }

    result
  }

  def optimiseWinR(work: IndexedSeq[Double],
                   minDesks: IndexedSeq[Int],
                   maxDesks: IndexedSeq[Int],
                   sla: Int,
                   weightChurn: Double,
                   weightPax: Double,
                   weightStaff: Double,
                   weightSla: Double): Seq[Int] = {
    engine.put("work", work.toArray)
    engine.put("xmax", maxDesks.toArray)
    engine.put("xmin", minDesks.toArray)
    engine.put("sla", sla)

    engine.put("w_churn", weightChurn)
    engine.put("w_pax", weightPax)
    engine.put("w_staff", weightStaff)
    engine.put("w_sla", weightSla)

    engine.eval("result <- optimise.win(work=work, xmin=xmin, xmax=xmax, sla=sla, weight.churn=w_churn, weight.pax=w_pax, weight.staff=w_staff, weight.sla=w_sla)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.map(_.toInt).toSeq
  }
}
