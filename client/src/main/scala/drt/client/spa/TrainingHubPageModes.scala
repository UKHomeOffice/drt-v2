package drt.client.spa

  sealed trait TrainingHubPageMode {
    val asString: String
  }

  object TrainingHubPageModes {
    def fromString(modeStr: String): TrainingHubPageMode = modeStr.toLowerCase match {
      case "trainingMaterial" => TrainingMaterial
      case "dropInBooking" => DropInBooking
      case unknown =>
        throw new Exception(s"Unknown training hub page mode '$unknown'")
    }

    case object TrainingMaterial extends TrainingHubPageMode {
      override val asString: String = "trainingMaterial"
    }

    case object DropInBooking extends TrainingHubPageMode {
      override val asString: String = "dropInBooking"
    }

  }

