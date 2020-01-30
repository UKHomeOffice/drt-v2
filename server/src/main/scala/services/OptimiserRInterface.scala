package services

import java.io.InputStream

import javax.script.{ScriptEngine, ScriptEngineManager}
import org.renjin.sexp.{DoubleVector, IntVector}
import org.slf4j.{Logger, LoggerFactory}

object OptimiserRInterface {
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

  def leftwardDesksR(work: IndexedSeq[Double],
                     xmin: IndexedSeq[Int],
                     xmax: IndexedSeq[Int],
                     blockSize: Int,
                     backlog: Double): IndexedSeq[Int] = {
    engine.put("work", work.toArray)
    engine.put("xmin", xmin.toArray)
    engine.put("xmax", xmax.toArray)
    engine.put("blockSize", blockSize)
    engine.put("backlog", backlog)
    engine.eval("result <- leftward.desks(work, xmin, xmax, blockSize, backlog)$desks")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toIndexedSeq.map(_.toInt)
  }

  def processWorkR(work: IndexedSeq[Double],
                   capacity: IndexedSeq[Int],
                   sla: Int,
                   qstart: IndexedSeq[Double]): ProcessedWork = {
    engine.put("work", work.toArray)
    engine.put("capacity", capacity.toArray)
    engine.put("sla", sla)
    engine.put("qstart", qstart.toArray)
    engine.eval("result <- process.work(work, capacity, sla, qstart)")
    val waits = engine.eval("result$wait").asInstanceOf[IntVector].toIntArray.toList
    val excessWait = engine.eval("result$excess.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
    val totalWait = engine.eval("result$total.wait").asInstanceOf[DoubleVector].getElementAsDouble(0)
    val utilisation = engine.eval("result$util").asInstanceOf[DoubleVector].toDoubleArray.toList
    val q = engine.eval("result$residual").asInstanceOf[DoubleVector].toDoubleArray.toIndexedSeq

    ProcessedWork(utilisation, waits, q, totalWait, excessWait)
  }

  def rollingFairXmaxR(work: IndexedSeq[Double],
                       xmin: IndexedSeq[Int],
                       blockSize: Int,
                       sla: Int,
                       targetWidth: Int,
                       rollingBuffer: Int): IndexedSeq[Int] = {
    engine.put("w", work.toArray)
    engine.put("xmin", xmin.toArray)
    engine.put("blockSize", blockSize)
    engine.put("sla", sla)
    engine.eval("rollingfairxmax <- rolling.fair.xmax(w, xmin=xmin, block.size=blockSize, sla=sla, target.width=60, rolling.buffer=120)")
    engine.eval("rollingfairxmax").asInstanceOf[DoubleVector].toIntArray
  }

  def runningAverageR(values: Iterable[Double], windowLength: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("windowLength", windowLength)
    engine.eval("result <- running.avg(values, windowLength)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def cumulativeSumR(values: Iterable[Double]): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.eval("result <- cumsum(values)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toList
  }

  def blockMeanR(values: Iterable[Double], blockWidth: Int): Iterable[Double] = {
    engine.put("values", values.toArray)
    engine.put("blockWidth", blockWidth)
    engine.eval("result <- block.mean(values, blockWidth)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.toIterable
  }

  def churnR(churnStart: Int, capacity: IndexedSeq[Int]): Int = {
    engine.put("churnStart", churnStart)
    engine.put("capacity", capacity.toArray)
    engine.eval("result <- churn(churnStart, capacity)")
    engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.head.toInt
  }

  def costR(work: IndexedSeq[Double],
            sla: Int,
            weightChurn: Double,
            weightPax: Double,
            weightStaff: Double,
            weightSla: Double,
            qStart: IndexedSeq[Double],
            churnStart: Int)(capacity: IndexedSeq[Int]): Cost = {
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
