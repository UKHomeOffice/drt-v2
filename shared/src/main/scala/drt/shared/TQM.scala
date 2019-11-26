package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import upickle.default.{macroRW, ReadWriter}

case class TQM(terminal: Terminal, queue: Queue, minute: MillisSinceEpoch)
  extends Ordered[TQM]
    with WithTimeAccessor
    with WithTerminal[TQM] {
  override def equals(o: scala.Any): Boolean = o match {
    case TQM(t, q, m) => t == terminal && q == queue && m == minute
    case _ => false
  }

  lazy val comparisonValue: Long = minute + (1024L * queue.orderingVal) + terminal.orderingValue

  override def compare(that: TQM): Int = this.comparisonValue.compareTo(that.comparisonValue)

  override def timeValue: MillisSinceEpoch = minute
}

object TQM {
  implicit val rw: ReadWriter[TQM] = macroRW

  def apply(crunchMinute: CrunchMinute): TQM = TQM(crunchMinute.terminal, crunchMinute.queue, crunchMinute.minute)

  def apply(terminalName: String, queueName: String, minute: MillisSinceEpoch): TQM = TQM(Terminal(terminalName), Queue(queueName), minute)

  def atTime: MillisSinceEpoch => TQM = (time: MillisSinceEpoch) => TQM("", "", time)
}
