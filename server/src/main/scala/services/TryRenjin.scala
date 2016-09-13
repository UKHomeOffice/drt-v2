package services

import java.io.InputStream
import javax.script.ScriptEngineManager

import org.renjin.sexp.{IntVector, DoubleVector}
import spatutorial.shared.{SimulationResult, CrunchResult}

import scala.collection.immutable.IndexedSeq

object TryRenjin {
  lazy val manager = new ScriptEngineManager()
  lazy val engine = manager.getEngineByName("Renjin")

  def crunch(workloads: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int]): CrunchResult = {
    loadOptimiserScript
    initialiseWorkloads(workloads)

    engine.put("xmax", maxDesks.toArray)
    engine.put("xmin", minDesks.toArray)
    engine.put("sla", 45)
    engine.put("weight_churn", 50)
    engine.put("weight_pax", 0.05)
    engine.put("weight_staff", 3)
    engine.put("weight_sla", 10)
    engine.eval("optimised <- optimise.win(w, xmin=xmin, xmax=xmax, sla=sla, weight.churn=weight_churn, weight.pax=weight_pax, weight.staff=weight_staff, weight.sla=weight_sla)")

    val deskRecs = engine.eval("optimised").asInstanceOf[DoubleVector]
    val deskRecsScala = (0 until deskRecs.length()) map (deskRecs.getElementAsInt(_))
    CrunchResult(deskRecsScala, runSimulation(deskRecsScala, "optimised"))
  }

  def processWork(workloads: Seq[Double], desks: Seq[Int]): SimulationResult = {
    loadOptimiserScript
    initialiseWorkloads(workloads)
    initialiseDesks("desks", desks)
    SimulationResult(desks.toVector, runSimulation(desks, "desks").toVector)
  }

  def runSimulation(deskRecsScala: Seq[Int], desks: String): Seq[Int] = {
    engine.eval("processed <- process.work(w, " + desks + ", 25, 0)")
    println("getting Processed$wait")
    val waitR = engine.eval("processed$wait").asInstanceOf[IntVector]
    println(s"got $waitR")
    val waitTimes: IndexedSeq[Int] = (0 until waitR.length()) map (waitR.getElementAsInt(_))
//    engine.eval("print(processed)")
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
