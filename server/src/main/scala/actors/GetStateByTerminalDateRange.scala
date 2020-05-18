package actors

import drt.shared.Terminals.Terminal
import drt.shared._

import scala.language.postfixOps


case class GetStateByTerminalDateRange(terminal: Terminal, start: SDateLike, end: SDateLike)
