package drt.shared

import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared.Terminals.Terminal
import upickle.default.{macroRW, ReadWriter}

case class TM(terminal: Terminal, minute: MillisSinceEpoch)
  extends Ordered[TM]
    with WithTimeAccessor
    with WithTerminal[TM] {
  override def equals(o: scala.Any): Boolean = o match {
    case TM(t, m) => t == terminal && m == minute
    case _ => false
  }

  lazy val comparisonVal: MillisSinceEpoch = minute + terminal.orderingVal

  override def compare(that: TM): Int = this.comparisonVal.compareTo(that.comparisonVal)

  override def timeValue: MillisSinceEpoch = minute
}

object TM {
  implicit val rw: ReadWriter[TM] = macroRW

  def apply(staffMinute: StaffMinute): TM = TM(staffMinute.terminal, staffMinute.minute)

  def apply(terminalName: String, minute: MillisSinceEpoch): TM = TM(Terminal(terminalName), minute)

  def atTime: MillisSinceEpoch => TM = (time: MillisSinceEpoch) => TM("", time)
}
