package services.crunch

import java.io.InputStream

import javax.script.{ScriptEngine, ScriptEngineManager}
import org.renjin.sexp.DoubleVector
import org.specs2.mutable.Specification
import services.{Optimiser, SDate}

class OptimiserSpec extends Specification {
  val manager: ScriptEngineManager = new ScriptEngineManager()
  val engine: ScriptEngine = manager.getEngineByName("Renjin")

  def loadOptimiserScript: AnyRef = {
    if (engine == null) throw new scala.RuntimeException("Couldn't load Renjin script engine on the classpath")
    val asStream: InputStream = getClass.getResourceAsStream("/optimisation-v6.R")

    val optimiserScript = scala.io.Source.fromInputStream(asStream)
    engine.eval(optimiserScript.bufferedReader())
  }

  //  "leftward.desks comparison" >> {
  //    loadOptimiserScript
  //
  //    val workloads = (1 to 360).map(_ => (Math.random() * 20).toInt)
  //    val minDesks = List.fill(360)(1)
  //    val maxDesks = List.fill(360)(10)
  //    engine.put("workload", workloads.toArray)
  //    engine.put("minDesks", minDesks.toArray)
  //    engine.put("maxDesks", maxDesks.toArray)
  //
  //    engine.eval("results <- leftward.desks(workload, xmin=minDesks, xmax=maxDesks, block.size=5, backlog=0)$desks")
  //    val rResult = engine.eval("results").asInstanceOf[DoubleVector].toDoubleArray.map(_.toInt).toList
  //
  //    val newResult = Optimiser.leftwardDesks(workloads.toList, minDesks, maxDesks, 5, 0)
  //
  //    rResult === newResult
  //  }

  //  "process.work comparison" >> {
  //    loadOptimiserScript
  //
  //    val desks = List(9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 4, 4, 4, 4, 4, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 8, 8, 8, 8, 8, 5, 5, 5, 5, 5, 15, 15, 15, 15, 15, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
  //    val work = List(10, 10, 17, 3, 9, 9, 14, 13, 5, 7, 18, 4, 16, 8, 6, 1, 3, 14, 9, 9, 8, 9, 1, 18, 11, 15, 13, 6, 12, 10, 19, 16, 2, 9, 10, 19, 14, 3, 5, 14, 4, 10, 12, 12, 4, 2, 3, 5, 12, 17, 16, 17, 14, 6, 4, 2, 8, 11, 13, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  //    engine.put("desks", desks.toArray)
  //    engine.put("work", work.toArray)
  //
  //    engine.eval("results <- process.work(work, desks, 18, 0)$wait")
  //    val rResult = engine.eval("results").asInstanceOf[IntVector].toIntArray.toList
  //
  //    val newResult = Optimiser.processWork(work, desks, 18, List(0)).waits
  //
  //    println(s"comparison: ${rResult.zip(newResult)}")
  //
  //    rResult === newResult
  //  }

  //  "rolling.fair.xmax comparison" >> {
  //    loadOptimiserScript
  //
  //    val workloads = (1 to 1440).map(_ => (Math.random() * 20).toInt)
  //    val maxDesks = (1 to 1440).map(_ => 10)
  //    val minDesks = (1 to 1440).map(_ => 1)
  //    engine.put("work", workloads.toArray)
  //    engine.put("xmax", maxDesks.toArray)
  //    engine.put("xmin", minDesks.toArray)
  //    engine.put("sla", 25)
  //    engine.put("adjustedSla", (0.75d * 25).toInt)
  //    engine.put("weight_churn", 50)
  //    engine.put("weight_pax", 0.05)
  //    engine.put("weight_staff", 3)
  //    engine.put("weight_sla", 10)
  //
  //    engine.eval("result <- rolling.fair.xmax(work, xmin=xmin, block.size=5, sla=adjustedSla, target.width=60, rolling.buffer=120)")
  //    val fairXmax = engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.map(_.toInt).toList
  //
  //    val newResult = Optimiser.rollingFairXmax(workloads.toList, minDesks.toList, 5, (0.75d * 25).toInt, 60, 120)
  //
  //    val compared = fairXmax.zip(newResult)
  //
  //    println(s"${fairXmax.length} / ${newResult.length}")
  //    println(s"compared: ${compared.takeRight(100).mkString(", ")}")
  //
  //    newResult === fairXmax
  //  }

  //  "block.mean comparison" >> {
  //    loadOptimiserScript
  //
  //    val stuff = 1 to 100
  //    val blockWidth = 25
  //    engine.put("stuff", stuff.toArray)
  //    engine.put("blockWidth", blockWidth)
  //    val rResult = engine.eval("block.mean(stuff, blockWidth)").asInstanceOf[DoubleVector].toDoubleArray
  //
  //    println(s"my result: ${stuff.grouped(blockWidth).flatMap(nos => List.fill(blockWidth)(nos.sum / blockWidth)).mkString(", ")}")
  //
  //    println(s"rResult: ${rResult.map(_.toInt).mkString(", ")}")
  //
  //    success
  //
  //  }

  "test stuff" >> {
    loadOptimiserScript

    val workloads = (1 to 10)
    val cap = (1 to 10).map(_ => 2)
    engine.put("work", workloads.toArray)
    engine.put("cap", cap.toArray)
    val result = engine.eval("((1 - work) * cap)").asInstanceOf[DoubleVector].toDoubleArray.mkString(", ")
    println(result)
    success
  }

  "optimise.win comparison" >> {
    skipped("exploratory")
    loadOptimiserScript

    val workloads = (1 to 720).map(_ => (Math.random() * 20))
    val maxDesks = (1 to 720).map(_ => 10)
    val minDesks = (1 to 720).map(_ => 1)
    val adjustedSla = (0.75d * 25).toInt

    val weightChurn = 50
    val weightPax = 0.05
    val weightStaff = 3
    val weightSla = 10

    engine.put("work", workloads.toArray)
    engine.put("xmax", maxDesks.toArray)
    engine.put("xmin", minDesks.toArray)
    engine.put("sla", adjustedSla)

    engine.put("w_churn", weightChurn)
    engine.put("w_pax", weightPax)
    engine.put("w_staff", weightStaff)
    engine.put("w_sla", weightSla)

    val rStartTime = SDate.now().millisSinceEpoch
    engine.eval("result <- optimise.win(work=work, xmin=xmin, xmax=xmax, sla=sla, weight.churn=w_churn, weight.pax=w_pax, weight.staff=w_staff, weight.sla=w_sla)")
    val rResult = engine.eval("result").asInstanceOf[DoubleVector].toDoubleArray.map(_.toInt).toSeq
    val rTook = SDate.now().millisSinceEpoch - rStartTime

    val sStartTime = SDate.now().millisSinceEpoch
    val newResult = Optimiser.optimiseWin(workloads.toList, minDesks.toList, maxDesks.toList, adjustedSla, weightChurn, weightPax, weightStaff, weightSla)
    val sTook = SDate.now().millisSinceEpoch - sStartTime
    println(s"r took ${rTook}ms. s took ${sTook}ms")

    newResult === rResult
  }
}
