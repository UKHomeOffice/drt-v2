package services

import java.io.InputStream
import javax.script.{ScriptEngine, ScriptEngineManager}

import org.renjin.sexp.{DoubleVector, IntVector}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.IndexedSeq
import scala.util.Try

case class OptimizerCrunchResult(
                         recommendedDesks: IndexedSeq[Int],
                         waitTimes: Seq[Int])

object TryRenjin {
  val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val manager = new ScriptEngineManager()

  def crunch(workloads: Iterable[Double], minDesks: Iterable[Int], maxDesks: Iterable[Int], config: OptimiserConfig): Try[OptimizerCrunchResult] = {
    val optimizer = Optimizer(engine = manager.getEngineByName("Renjin"))
    optimizer.crunch(workloads, minDesks, maxDesks, config)
  }

  def runSimulationOfWork(workloads: Iterable[Double], desks: Iterable[Int], config: OptimiserConfig): Seq[Int] = {
    val optimizer = Optimizer(engine = manager.getEngineByName("Renjin"))
    optimizer.processWork(workloads, desks, config)
  }

  case class Optimizer(engine: ScriptEngine) {
    def crunch(workloads: Iterable[Double], minDesks: Iterable[Int], maxDesks: Iterable[Int], config: OptimiserConfig): Try[OptimizerCrunchResult] = {
      val tryCrunchRes = Try {
        loadOptimiserScript
        initialiseWorkloads(workloads)

        engine.put("xmax", maxDesks.toArray)
        engine.put("xmin", minDesks.toArray)
        engine.put("sla", config.sla)
        engine.put("adjustedSla", 0.75d * config.sla)
        engine.put("weight_churn", 50)
        engine.put("weight_pax", 0.05)
        engine.put("weight_staff", 3)
        engine.put("weight_sla", 10)

        val adjustedXMax = if (workloads.size > 60) {
          engine.eval("rollingfairxmax <- rolling.fair.xmax(w, xmin=xmin, block.size=5, sla=adjustedSla, target.width=60, rolling.buffer=120)")
          val fairXmax = engine.eval("rollingfairxmax").asInstanceOf[DoubleVector]
          fairXmax.toIntArray.toSeq.zip(maxDesks).map { case (fair, orig) => List(fair, orig).min }
        } else maxDesks

        engine.put("adjustedXMax", adjustedXMax.toArray)

        engine.eval("optimised <- optimise.win(w, xmin=xmin, xmax=adjustedXMax, sla=sla, weight.churn=weight_churn, weight.pax=weight_pax, weight.staff=weight_staff, weight.sla=weight_sla)")

        val deskRecs = engine.eval("optimised").asInstanceOf[DoubleVector]
        val deskRecsScala = (0 until deskRecs.length()) map deskRecs.getElementAsInt
        OptimizerCrunchResult(deskRecsScala, runSimulation("optimised", config))
      }
      tryCrunchRes
    }

    def processWork(workloads: Iterable[Double], desks: Iterable[Int], config: OptimiserConfig): Seq[Int] = {
      loadOptimiserScript
      log.debug(s"Setting ${workloads.size} workloads & ${desks.size} desks")
      initialiseWorkloads(workloads)
      initialiseDesks("desks", desks)
      runSimulation("desks", config).toList
    }

    def runSimulation(desks: String, config: OptimiserConfig): Seq[Int] = {
      engine.put("sla", config.sla)
      engine.eval("processed <- process.work(w, " + desks + ", sla, 0)")

      val waitRV = engine.eval(s"processed$$wait").asInstanceOf[IntVector]
      val waitTimes: IndexedSeq[Int] = (0 until waitRV.length()) map waitRV.getElementAsInt

      waitTimes
    }

    def initialiseWorkloads(workloads: Iterable[Double]): Unit = {
      engine.put("w", workloads.toArray)
    }

    def initialiseDesks(varName: String, desks: Iterable[Int]): Unit = {
      engine.put(varName, desks.toArray)
    }

    def loadOptimiserScript: AnyRef = {
      if (engine == null) throw new scala.RuntimeException("Couldn't load Renjin script engine on the classpath")
      val asStream: InputStream = getClass.getResourceAsStream("/optimisation-v6.R")

      val optimiserScript = scala.io.Source.fromInputStream(asStream)
      engine.eval(optimiserScript.bufferedReader())
    }
  }

}
