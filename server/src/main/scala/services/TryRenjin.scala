package services

import java.io.InputStream
import javax.script.{ScriptEngine, ScriptEngineManager}

import org.renjin.sexp.{IntVector, DoubleVector}
import org.slf4j.LoggerFactory
import spatutorial.shared._

import scala.collection.immutable.Seq
import scala.collection.immutable.IndexedSeq
import scala.util.{Success, Failure, Try}

case class OptimizerConfig(sla: Int)

object TryRenjin {
  val log = LoggerFactory.getLogger(getClass)
  lazy val manager = new ScriptEngineManager()
  def crunch(workloads: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[CrunchResult] = {
    val optimizer = Optimizer(engine = manager.getEngineByName("Renjin"))
    optimizer.crunch(workloads, minDesks, maxDesks, config)
  }
  def processWork(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): SimulationResult = {
    val optimizer = Optimizer(engine = manager.getEngineByName("Renjin"))
    optimizer.processWork(workloads, desks, config)
  }

  case class Optimizer(engine: ScriptEngine) {
    def crunch(workloads: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[CrunchResult] = {
      val tryCrunchRes = Try {
        loadOptimiserScript
        initialiseWorkloads(workloads)

        engine.put("xmax", maxDesks.toArray)
        engine.put("xmin", minDesks.toArray)
        engine.put("sla", config.sla)
        engine.put("weight_churn", 50)
        engine.put("weight_pax", 0.05)
        engine.put("weight_staff", 3)
        engine.put("weight_sla", 10)
        engine.eval("optimised <- optimise.win(w, xmin=xmin, xmax=xmax, sla=sla, weight.churn=weight_churn, weight.pax=weight_pax, weight.staff=weight_staff, weight.sla=weight_sla)")

        val deskRecs = engine.eval("optimised").asInstanceOf[DoubleVector]
        val deskRecsScala = (0 until deskRecs.length()) map (deskRecs.getElementAsInt(_))
        CrunchResult(deskRecsScala, runSimulation(deskRecsScala, "optimised", config))
      }
      tryCrunchRes
    }

    def processWork(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): SimulationResult = {
      println(s"${workloads.length}, ${desks.length}")
      loadOptimiserScript
      initialiseWorkloads(workloads)
      initialiseDesks("desks", desks)
      SimulationResult(desks.zipWithIndex.map(t => DeskRec(t._2, t._1)).toIndexedSeq, runSimulation(desks, "desks", config).toVector)
    }

    def runSimulation(deskRecsScala: Seq[Int], desks: String, config: OptimizerConfig): Seq[Int] = {
      engine.put("sla", config.sla)
      engine.eval("processed <- process.work(w, " + desks + ", sla=sla, 0)")

      val waitR = engine.eval(s"processed$$wait").asInstanceOf[IntVector]

      val waitTimes: IndexedSeq[Int] = (0 until waitR.length()) map (waitR.getElementAsInt(_))

      waitTimes
    }

    def initialiseWorkloads(workloads: Seq[Double]): Unit = {
      engine.put("w", workloads.toArray)
    }

    def initialiseDesks(varName: String, desks: Seq[Int]): Unit = {
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
