package drt.client.components

import drt.shared.CrunchApi.MillisSinceEpoch

trait DisplayLabel {
  def getLabel(): String

}

trait DisplayShortLabel {
  def getLabel(needShort: Boolean): String
}

case class ScheduleDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel with DisplayShortLabel {
  def getLabel() = {
    if (isMobile) {
      "Sch"
    } else {
      "Schedule"
    }
  }

  def getLabel(needShort: Boolean): String =
    if (needShort || isMobile) {
      "Sch"
    } else {
      "Schedule"
    }
}

case class EstimatedDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  def getLabel() = {
    if (isMobile) {
      "Est"
    } else {
      "Estimate"
    }
  }
}

case class TouchdownDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  def getLabel() = {
    if (isMobile) {
      "Tou"
    } else {
      "Touchdown"
    }
  }
}

case class EstimatedChoxDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  def getLabel() = {
    if (isMobile) {
      "Est Chox"
    } else {
      "Estimated Chox"
    }
  }
}

case class ActualChoxDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  def getLabel() = {
    if (isMobile) {
      "Act Chox"
    } else {
      "Actual Chox"
    }
  }
}

case class PredicatedDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  def getLabel() = {
    if (isMobile) {
      "Pre"
    } else {
      "Predicated"
    }
  }
}

case class ExpPcpDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel with DisplayShortLabel {
  override def getLabel: String = {
    if (isMobile) {
      "Ex PCP"
    } else {
      "Exp PCP"
    }
  }

  def getLabel(needShort: Boolean): String =
    if (needShort || isMobile) {
      "Ex PCP"
    } else {
      "Exp PCP"
    }

}

case class EstimatedPCPaxDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel with DisplayShortLabel {
  override def getLabel: String = {
    if (isMobile) {
      "Ex Pcp Px"
    } else {
      "Est PCP Pax"
    }
  }

  def getLabel(needShort: Boolean): String =
    if (needShort || isMobile) {
      "Ex Pcp Px"
    } else {
      "Est PCP Pax"
    }

}

case class ExpectedDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel with DisplayShortLabel {
  override def getLabel: String = {
    if (isMobile) {
      "Exp"
    } else {
      "Expected"
    }
  }

  def getLabel(needShort: Boolean): String =
    if (needShort || isMobile) {
      "Exp"
    } else {
      "Expected"
    }

}

case class OriginDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel {
  override def getLabel: String = {
    if (isMobile) {
      "Ori"
    } else {
      "Origin"
    }
  }

}

case class GateStandDisplayLabel()(implicit isMobile: Boolean) extends DisplayLabel with DisplayShortLabel {
  override def getLabel: String = {
    if (isMobile) {
      "Gt/St"
    } else {
      "Gate / Stand"
    }
  }

  def getLabel(needShort: Boolean): String = {
    if (needShort || isMobile) {
      "Gt/St"
    } else {
      "Gate / Stand"
    }
  }


}

case class ArrivalDisplayTime(timeLabel: DisplayLabel, time: Option[MillisSinceEpoch])



