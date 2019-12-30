package services

import java.io.InputStream

import javax.script.{ScriptEngine, ScriptEngineManager}
import org.renjin.sexp.{DoubleVector, IntVector}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.util.{Success, Try}

object Optimiser {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val manager: ScriptEngineManager = new ScriptEngineManager()
  val engine: ScriptEngine = manager.getEngineByName("Renjin")
  var checkAllAgainstR = false
  var checkOptimiserAgainstR = false

  def loadOptimiserScript: AnyRef = {
    if (engine == null) throw new scala.RuntimeException("Couldn't load Renjin script engine on the classpath")
    val asStream: InputStream = getClass.getResourceAsStream("/optimisation-v6.R")

    val optimiserScript = scala.io.Source.fromInputStream(asStream)
    engine.eval(optimiserScript.bufferedReader())
  }

  loadOptimiserScript

  val weightSla = 10
  val weightChurn = 50
  val weightPax = 0.05
  val weightStaff = 3
  val blockSize = 5
  val targetWidth = 60
  val rollingBuffer = 120

  def crunch(workloads: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    log.info(s"Starting optimisation for ${workloads.length} minutes of workload")
    val fairMaxD = rollingFairXmax(workloads.toList, minDesks.toList, blockSize, config.sla, targetWidth, rollingBuffer)
    val desks = optimiseWin(workloads.toList, minDesks.toList, fairMaxD, config.sla, weightChurn, weightPax, weightStaff, weightSla)
    log.info(s"Finished. Desk recs: ${/*desks.mkString(", ")*/}")
    log.info(s"Finished. Starting simulation for wait times")
    val waits = processWork(workloads.toList, desks.toList, config.sla, List()).waits
    log.info(s"Finished. Wait times: ${/*waits.mkString(", ")*/}")

    Success(OptimizerCrunchResult(desks.toIndexedSeq, waits))
  }

  def runSimulationOfWork(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Seq[Int] =
    Optimiser.processWork(workloads.toList, desks.toList, config.sla, List()).waits

  def approx(x: Seq[Int], y: Seq[Int], i: Seq[Double]): List[Double] = {
    val diffX = x(1) - x.head
    val diffY = y(1) - y.head
    val ratio = diffY.toDouble / diffX
    i.map(_ * ratio).toList
  }

  def leftwardDesks(work: List[Double], xmin: List[Int], xmax: List[Int], blockSize: Int, backlog: Double): List[Int] = {
    val workWithMinMaxDesks: Iterator[(List[Double], (List[Int], List[Int]))] = work.grouped(blockSize).zip(xmin.grouped(blockSize).zip(xmax.grouped(blockSize)))

    val result = workWithMinMaxDesks.foldLeft((List[Int](), backlog)) {
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
    }._1

    if (checkAllAgainstR) {
      val rResult = leftwardDesksR(work, xmin, xmax, blockSize, backlog)

      if (rResult != result) {
        println(s"leftwardDesks mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def leftwardDesksR(work: List[Double], xmin: List[Int], xmax: List[Int], blockSize: Int, backlog: Double): List[Int] = {
    engine.put("work", work.toArray)
    engine.put("xmin", xmin.toArray)
    engine.put("xmax", xmax.toArray)
    engine.put("blockSize", blockSize)
    engine.put("backlog", backlog)
    engine.eval("result <- leftward.desks(work, xmin, xmax, blockSize, backlog)$desks")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList.map(_.toInt)
  }

  case class ProcessedWork(util: List[Double], waits: List[Int], residual: List[Double], totalWait: Double, excessWait: Double)

  def processWork(work: List[Double], capacity: List[Int], sla: Int, qstart: List[Double]): ProcessedWork = {
    var q = qstart
    var totalWait: Double = 0d
    var excessWait: Double = 0d

    val (finalWait, finalUtil) = work.indices.foldLeft((List[Int](), List[Double]())) {
      case ((wait, util), minute) =>
        q = work(minute) :: q
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
            q = q.dropRight(1) ::: List(nextWorkToProcess - resource)
            resource = 0
            age = 0
          }
        }

        (q.size :: wait, (1 - (resource / capacity(minute))) :: util)
    }

    val waitReversed = finalWait.reverse
    val utilReversed = finalUtil.reverse

    if (checkAllAgainstR) {
      val rResult = processWorkR(work, capacity, sla, qstart)

      if (rResult.util != utilReversed) {
        println(s"processWork util mismatch:\n$utilReversed\n${rResult.util}")
      }

      if (rResult.waits != waitReversed) {
        println(s"processWork waits mismatch:\n$waitReversed\n${rResult.waits}")
      }

      if (rResult.residual != q) {
        println(s"processWork residual mismatch:\n$q\n${rResult.residual}")
      }

      if (rResult.totalWait != totalWait) {
        println(s"processWork totalWait mismatch:\n$totalWait\n${rResult.totalWait}")
      }

      if (rResult.excessWait != excessWait) {
        println(s"processWork excessWait mismatch:\n$excessWait\n${rResult.excessWait}")
      }
    }

    ProcessedWork(utilReversed, waitReversed, q, totalWait, excessWait)
  }

  def processWorkR(work: List[Double], capacity: List[Int], sla: Int, qstart: List[Double]): ProcessedWork = {
    engine.put("work", work.toArray)
    engine.put("capacity", capacity.toArray)
    engine.put("sla", sla)
    engine.put("qstart", qstart.toArray)
    engine.eval("result <- process.work(work, capacity, sla, qstart)")
    val waits = engine.eval("result$wait").asInstanceOf[IntVector].toIntArray.toList
    val excessWait = engine.eval("result$excess.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
    val totalWait = engine.eval("result$total.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
    val util: List[Double] = engine.eval("result$util").asInstanceOf[DoubleVector].toDoubleArray.toList
    val q = engine.eval("result$residual").asInstanceOf[DoubleVector].toDoubleArray.toList

    ProcessedWork(util, waits, q, totalWait, excessWait)
  }

  def rollingFairXmax(work: List[Double], xmin: List[Int], blockSize: Int, sla: Int, targetWidth: Int, rollingBuffer: Int): List[Int] = {
    val workWithOverrun = work ++ List.fill(targetWidth)(0d)
    val xminWithOverrun = xmin ++ List.fill(targetWidth)(xmin.takeRight(1).head)

    (workWithOverrun.indices by targetWidth).foldLeft(List[Int]()) { case (acc, startSlot) =>
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
          val trialDesks = leftwardDesks(winWork, winXmin, List.fill(winXmin.size)(winXmax), blockSize, backlog)
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

      val newXmax = acc ::: List.fill(targetWidth)(winXmax)
      0 until targetWidth foreach (j => backlog = List(backlog + winWork(j) - newXmax(winStart), 0).max)
      newXmax
    }.take(work.size)
  }

  def runningAverage(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    val averages = values
      .sliding(windowLength)
      .map(_.map(_ / windowLength).sum
      ).toList

    val result = List.fill(windowLength - 1)(averages.head) ::: averages

    if (checkAllAgainstR) {
      val rResult = runningAverageR(values, windowLength)
      if (rResult.map(x => BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble) != result.map(x => BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble)) {
        println(s"runningAverage mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def runningAverageR(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("windowLength", windowLength)
    engine.eval("result <- running.avg(values, windowLength)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def cumulativeSum(values: Iterable[Double]): Iterable[Double] = {
    val result = values.foldLeft(List[Double]()) {
      case (Nil, element) => List(element)
      case (head :: tail, element) => element + head :: head :: tail
    }.reverse

    if (checkAllAgainstR) {
      val rResult = cumulativeSumR(values)
      if (rResult != result) {
        println(s"cumulativeSum mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def cumulativeSumR(values: Iterable[Double]): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.eval("result <- cumsum(values)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def blockMean(values: Iterable[Double], blockWidth: Int): Iterable[Double] = {
    val result = values
      .grouped(blockWidth)
      .flatMap(nos => List.fill(blockWidth)(nos.sum / blockWidth))
      .toIterable

    if (checkAllAgainstR) {
      val rResult = blockMeanR(values, blockWidth)

      if (rResult != result) {
        println(s"blockMean mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def blockMeanR(values: Iterable[Double], blockWidth: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("blockWidth", blockWidth)
    engine.eval("result <- block.mean(values, blockWidth)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toIterable
  }

  def blockMax(values: Iterable[Double], blockWidth: Int): Iterable[Double] = {
    val result = values
      .grouped(blockWidth)
      .flatMap(nos => List.fill(blockWidth)(nos.max))
      .toIterable

    if (checkAllAgainstR) {
      val rResult = blockMeanR(values, blockWidth)

      if (rResult != result) {
        println(s"blockMax mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def seqR(from: Int, by: Int, length: Int): List[Int] = (0 to length map (i => (i + from) * by)).toList

  def churn(churnStart: Int, capacity: List[Int]): Int = {
    val result = capacity.zip((churnStart :: capacity))
      .collect { case (x, xlag) => x - xlag }
      .filter(_ > 0)
      .sum

    if (checkAllAgainstR) {
      val rResult = churnR(churnStart, capacity)

      if (rResult != result) {
        println(s"churn mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def churnR(churnStart: Int, capacity: List[Int]): Int = {
    engine.put("churnStart", churnStart)
    engine.put("capacity", capacity.toArray)
    engine.eval("result <- churn(churnStart, capacity)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
  }

  case class Cost(paxPenalty: Double, slaPenalty: Double, staffPenalty: Double, churnPenalty: Int, totalPenalty: Double)

  def cost(work: List[Double], sla: Int, weightChurn: Double, weightPax: Double, weightStaff: Double, weightSla: Double, qStart: List[Double], churnStart: Int)(capacity: List[Int]): Cost = {
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
      val startSlots: List[Double] = 0 :: backlogBoundaries.dropRight(1).map(_.floor)
      val endSlots: List[Double] = backlogBoundaries.map(_.floor)
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
        .map {
          case (true, (x, y)) => x * y
          case _ => 0
        }.sum

      simres = simres.copy(totalWait = newTotalWait, excessWait = newExcessWait)
    }

    val paxPenalty = simres.totalWait
    val slaPenalty = simres.excessWait
    val staffPenalty = simres.util.zip(capacity).map { case (u, c) => (1 - u) * c.toDouble }.sum
    val churnPenalty = churn(churnStart, capacity :+ finalCapacity)

    val totalPenalty = (weightPax * paxPenalty) +
      (weightStaff * staffPenalty.toDouble) +
      (weightChurn * churnPenalty.toDouble) +
      (weightSla * slaPenalty.toDouble)

//    println(s"$weightPax * $paxPenalty + $weightStaff * $staffPenalty + $weightChurn * $churnPenalty + $weightSla * $slaPenalty = $totalPenalty")

    val result = Cost(paxPenalty.toInt, slaPenalty.toInt, staffPenalty, churnPenalty, totalPenalty)

    if (checkAllAgainstR) {
      val rResult = costR(work, sla, weightChurn, weightPax, weightStaff, weightSla, qStart, churnStart)(capacity)

      if (rResult.staffPenalty != result.staffPenalty) {
        println(s"cost staffPenalty mismatch:\n${result.staffPenalty}\n${rResult.staffPenalty}")
      }

      if (rResult.churnPenalty != result.churnPenalty) {
        println(s"cost churnPenalty mismatch:\n${result.churnPenalty}\n${rResult.churnPenalty}")
      }

      if (rResult.paxPenalty != result.paxPenalty) {
        println(s"cost paxPenalty mismatch:\n${result.paxPenalty}\n${rResult.paxPenalty}")
      }

      if (rResult.slaPenalty != result.slaPenalty) {
        println(s"cost slaPenalty mismatch:\n${result.slaPenalty}\n${rResult.slaPenalty}")
      }

      if (rResult.totalPenalty != result.totalPenalty) {
        println(s"cost totalPenalty mismatch:\n${result.totalPenalty}\n${rResult.totalPenalty}")
      }
    }

    result
  }

  def costR(work: List[Double], sla: Int, weightChurn: Double, weightPax: Double, weightStaff: Double, weightSla: Double, qStart: List[Double], churnStart: Int)(capacity: List[Int]): Cost = {
    engine.put("work", work.toArray)
    engine.put("sla", sla)
    engine.put("weightChurn", weightChurn.toInt)
    engine.put("weightPax", weightPax)
    engine.put("weightStaff", weightStaff.toInt)
    engine.put("weightSla", weightSla.toInt)
    engine.put("qStart", qStart.toArray)
    engine.put("churnStart", churnStart)
    engine.put("capacity", capacity.toArray)
    engine.eval("result <- cost(work, capacity, sla, weightPax, weightStaff, weightChurn, weightSla, qstart = qStart, churn.start = churnStart)")
    val rpax = engine.eval("result$pax").asInstanceOf[DoubleVector].toIntArray.head
    val rsla = engine.eval("result$sla.p").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
    val rstaff = engine.eval("result$staff").asInstanceOf[DoubleVector].toDoubleArray.head
    val rchurn = engine.eval("result$churn").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
    val rtotal = engine.eval("result$total").asInstanceOf[DoubleVector].toDoubleArray.head
    Cost(rpax, rsla, rstaff, rchurn, rtotal)
  }

  def neighbouringPoints(x0: Int, xmin: Int, xmax: Int): Seq[Int] = (xmin to xmax)
    .filterNot(_ == x0)
    .sortBy(x => (x - x0).abs)

  def branchBound(startingX: List[Int], cost: List[Int] => Cost, xmin: List[Int], xmax: List[Int], concavityLimit: Int): Iterable[Int] = {
    val x: mutable.Seq[Int] = mutable.Seq() ++ startingX
    var incumbent = startingX
    val minutes = x.length
    var bestSoFar = cost(incumbent).totalPenalty
    val candidates: mutable.Seq[Seq[Int]] = mutable.Seq() ++ (0 until minutes).map(i => neighbouringPoints(startingX(i), xmin(i), xmax(i)))

    var cursor = minutes - 1

    while (cursor >= 0) {
      while (candidates(cursor).nonEmpty) {
        x(cursor) = candidates(cursor).take(1).head
        candidates(cursor) = candidates(cursor).drop(1)

        val trialPenalty = cost(x.toList).totalPenalty

        if (trialPenalty > bestSoFar + concavityLimit) {
          if (x(cursor) > incumbent(cursor)) {
            candidates(cursor) = candidates(cursor).filter(_ < x(cursor))
          } else {
            candidates(cursor) = candidates(cursor).filter(_ > x(cursor))
          }
        } else {
          if (trialPenalty < bestSoFar) {
            incumbent = x.toList
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

  def optimiseWin(work: List[Double], minDesks: List[Int], maxDesks: List[Int], sla: Int, weightChurn: Double, weightPax: Double, weightStaff: Double, weightSla: Double): Seq[Int] = {
    val blockWidth = 15
    val concavityLimit = 30
    val winStep = 60
    val smoothingWidth = blockWidth
    val winWidth = List(90, work.length).min

    var winStart = 0
    var winStop = winWidth
    var qStart = List(0d)
    var churnStart = 0

    val desks: mutable.Seq[Int] = mutable.Seq[Int]() ++ blockMean(runningAverage(work, smoothingWidth), blockWidth)
      .map(_.ceil.toInt)
      .toList
      .zip(maxDesks)
      .map {
        case (d, max) => List(d, max).min
      }
      .zip(minDesks)
      .map {
        case (d, min) => List(d, min).max
      }

    def myCost(costWork: List[Double], costQStart: List[Double], costChurnStart: Int)(capacity: List[Int]): Cost =
      cost(costWork, sla, weightChurn, weightPax, weightStaff, weightSla, costQStart, costChurnStart)(capacity.flatMap(c => List.fill(blockWidth)(c)))

    var shouldStop = false

    do {
      val currentWork = work.slice(winStart, winStop)
      val desksSlice: List[Int] = desks.slice(winStart, winStop).toList
      val desksSliceGrouped: List[List[Int]] = desksSlice.grouped(blockWidth).toList
      val blockGuess = desksSliceGrouped.map(_.head)
      val xminCondensed = minDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head)
      val xmaxCondensed = maxDesks.slice(winStart, winStop).grouped(blockWidth).map(_.head)

      val windowIndices = winStart until winStop
      branchBound(blockGuess, myCost(currentWork, qStart, churnStart), xminCondensed.toList, xmaxCondensed.toList, concavityLimit)
        .flatMap(o => List.fill(blockWidth)(o))
        .zip(windowIndices)
        .foreach {
          case (d, i) => desks(i) = d
        }

      shouldStop = winStop == work.length

      if (!shouldStop) {
        val stop = winStart + winStep
        val workToProcess = work.slice(winStart, stop)
        val desksToProcess = desks.slice(winStart, stop).toList
        qStart = processWork(workToProcess, desksToProcess, sla, qStart).residual
        churnStart = desks(stop)
        winStart = winStart + winStep
        winStop = List(winStop + winStep, work.length).min
      }
    } while (!shouldStop)

    val result = desks.toList

    if (checkOptimiserAgainstR) {
      val rResult = optimiseWinR(work, minDesks, maxDesks, sla, weightChurn, weightPax, weightStaff, weightSla)

      if (rResult != result) {
        println(s"optimiseWin mismatch:\n$result\n$rResult")
      }
    }

    result
  }

  def optimiseWinR(work: List[Double], minDesks: List[Int], maxDesks: List[Int], sla: Int, weightChurn: Double, weightPax: Double, weightStaff: Double, weightSla: Double): Seq[Int] = {
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
