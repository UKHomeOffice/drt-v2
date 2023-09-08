package drt.client.spa

  sealed trait TrainingHubPageMode {
    val asString: String
  }

  object TrainingHubPageModes {
    def fromString(modeStr: String): TrainingHubPageMode = modeStr.toLowerCase match {
      case "trainingMaterial" => TrainingMaterial
      case "seminarBooking" => SeminarBooking
      case unknown =>
        throw new Exception(s"Unknown training hub page mode '$unknown'")
    }

    case object TrainingMaterial extends TrainingHubPageMode {
      override val asString: String = "trainingMaterial"
    }

    case object SeminarBooking extends TrainingHubPageMode {
      override val asString: String = "seminarBooking"
    }

  }

