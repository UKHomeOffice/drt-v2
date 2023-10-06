package drt.client.spa


sealed trait TerminalPageMode {
  val asString: String
}

object TerminalPageModes {
  def fromString(modeStr: String): TerminalPageMode = modeStr.toLowerCase match {
    case "dashboard" => Dashboard
    case "current" => Current
    case "snapshot" => Snapshot
    case "planning" => Planning
    case "staffing" => Staffing
    case unknown =>
      throw new Exception(s"Unknown terminal page mode '$unknown'")
  }

  case object Dashboard extends TerminalPageMode {
    override val asString: String = "dashboard"
  }

  case object Current extends TerminalPageMode {
    override val asString: String = "current"
  }

  case object Snapshot extends TerminalPageMode {
    override val asString: String = "snapshot"
  }

  case object Planning extends TerminalPageMode {
    override val asString: String = "planning"
  }

  case object Staffing extends TerminalPageMode {
    override val asString: String = "staffing"
  }
}


