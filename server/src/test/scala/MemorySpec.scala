import drt.shared.Terminals.{T2, T3, T4, T5}
import drt.shared.{Queues, TQM}
import org.specs2.mutable.Specification
import services.graphstages.Crunch.LoadMinute


sealed trait Port

object Ports {
  object Lhr extends Port
}

class MemorySpec extends Specification {
  val runtime = Runtime.getRuntime
  val kb = 1024
  val mb = 1024 * 1024

  "Given a Map of stuff " +
    "When I transform it to a Set " +
    "How much memory allocation is triggered" >> {
    skipped("exploratory")

    val terminals = Seq(T2, T3, T4, T5)
    val queues = Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate, Queues.FastTrack)
    val sixMonthsInMinutes = 180 * 24 * 60 * 60
    val minutesRange = 1548674630957L to (1548674630957L + (sixMonthsInMinutes * 1000L)) by 60000L
    val minutes: Seq[Long] = minutesRange.take(sixMonthsInMinutes)

    logMemoryUsage("before seq creation")

    val stuff = for {
      t <- terminals
      q <- queues
      m <- minutes
    } yield { TQM(t, q, m) -> LoadMinute(t, q, Math.random(), Math.random(), m) }

    println(s"generated a ${stuff.length} element Seq")

    logMemoryUsage("before toMap")
    val minuteMap = stuff.toMap
    logMemoryUsage("after toMap")
    minuteMap.keys
    logMemoryUsage("after keys")
    minuteMap.values.toSet
    logMemoryUsage("after values.toSet")

    true
  }

  def logMemoryUsage(msg: String): Unit = {
    println(s"** $msg")
    println("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb) + "Mb"
  }
}
