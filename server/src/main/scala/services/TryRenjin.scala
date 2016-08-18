package services

import java.io.InputStream
import javax.script.ScriptEngineManager

import org.renjin.sexp.{IntVector, DoubleVector}
import spatutorial.shared.CrunchResult

object TryRenjin {
  lazy val manager = new ScriptEngineManager()
  lazy val engine = manager.getEngineByName("Renjin")

  def crunch(workloads: Seq[Double]): CrunchResult = {
    loadOptimiserScript
    initialiseWorkloads(workloads)

    engine.eval("optimised <- optimise.win(w, xmax=15)")

    val deskRecs = engine.eval("optimised").asInstanceOf[DoubleVector]
    val deskRecsScala = (0 until deskRecs.length()) map (deskRecs.getElementAsDouble(_))
    CrunchResult(deskRecsScala, processWork(deskRecsScala, "optimised"))
  }

  def processWork(workloads: Seq[Double], desks: Seq[Double]): CrunchResult = {
    loadOptimiserScript
    initialiseWorkloads(workloads)
    initialiseDesks("desks", desks)
    CrunchResult(desks, processWork(desks, "desks"))
  }

  def processWork(deskRecsScala: Seq[Double], desks: String): Seq[Double] = {
    engine.eval("processed <- process.work(w, " + desks + ", 25, 0)")
    println("getting Processed$wait")
    val waitR = engine.eval("processed$wait").asInstanceOf[IntVector]
    println(s"got $waitR")
    val waitTimes = (0 until waitR.length()) map (waitR.getElementAsInt(_).toDouble)
    engine.eval("print(processed)")
    waitTimes
  }

  def initialiseWorkloads(workloads: Seq[Double]): AnyRef = {
    val workloadAsRVector = "c" + workloads.mkString("(", ",", ")")
    val s: String = s"w <- $workloadAsRVector"
    engine.eval(s)
  }

  def initialiseDesks(varName: String, desks: Seq[Double]): AnyRef = {
    val desksR = "c" + desks.mkString("(", ",", ")")
    val s: String = s"$varName <- $desksR"
    engine.eval(s)
  }

  def loadOptimiserScript: AnyRef = {
    if (engine == null) throw new scala.RuntimeException("Couldn't load Renjin script engine on the classpath")
    val asStream: InputStream = getClass.getResourceAsStream("/optimisation-v6.R")

    val optimiserScript = scala.io.Source.fromInputStream(asStream)
    engine.eval(optimiserScript.bufferedReader())
  }

}
