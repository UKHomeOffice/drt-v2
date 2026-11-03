package services.crunch.deskrecs

import play.api.Configuration

import scala.concurrent.duration._

sealed trait QueueAllocationMode {
  def name: String
}

object QueueAllocationMode {
  case object Legacy extends QueueAllocationMode {
    override val name: String = "legacy"
  }

  case object Neutral extends QueueAllocationMode {
    override val name: String = "neutral"
  }

  def parse(s: String): QueueAllocationMode = s match {
    case "legacy"  => Legacy
    case "neutral" => Neutral
    case _         => throw new IllegalArgumentException("Queue allocation mode must be exactly legacy or neutral")
  }
}

sealed trait QueueAllocationKind {
  def name: String
}

object QueueAllocationKind {
  case object Recommendations extends QueueAllocationKind {
    override val name: String = "recommendations"
  }

  case object Deployments extends QueueAllocationKind {
    override val name: String = "deployments"
  }
}

case class NeutralAllocationSettings(maxDuration: FiniteDuration, fallbackDuration: FiniteDuration)

case class QueueAllocationSettings(
    recommendations: QueueAllocationMode = QueueAllocationMode.Legacy,
    deployments: QueueAllocationMode = QueueAllocationMode.Legacy,
    neutral: Option[NeutralAllocationSettings] = None,
    cancelGrace: Option[FiniteDuration] = None,
    shutdownGrace: Option[FiniteDuration] = None
) {
  def mode(kind: QueueAllocationKind): QueueAllocationMode = kind match {
    case QueueAllocationKind.Recommendations => recommendations
    case QueueAllocationKind.Deployments     => deployments
  }

  def enabled: Boolean =
    recommendations != QueueAllocationMode.Legacy || deployments != QueueAllocationMode.Legacy
}

object QueueAllocationSettings {
  val legacy: QueueAllocationSettings = QueueAllocationSettings()

  /** The time the application allows for services to stop before it unbinds; shutdown grace must fit within it. */
  def shutdownPhaseTimeout(config: Configuration): Option[FiniteDuration] =
    config.getOptional[FiniteDuration]("pekko.coordinated-shutdown.phases.before-service-unbind.timeout")
      .orElse(config.getOptional[FiniteDuration]("pekko.coordinated-shutdown.default-phase-timeout"))

  def fromConfiguration(config: Configuration): QueueAllocationSettings = {
    def mode(key: String): QueueAllocationMode =
      config.getOptional[String](key).map(QueueAllocationMode.parse).getOrElse(QueueAllocationMode.Legacy)

    def duration(key: String): Option[FiniteDuration] = {
      val value = config.getOptional[FiniteDuration](key)
      require(value.forall(_ > Duration.Zero), s"$key must be positive")
      value
    }

    def requireDuration(key: String, value: Option[FiniteDuration], required: Boolean): Unit =
      require(!required || value.isDefined, s"$key is required for the configured queue allocation modes")

    val recommendations = mode("feature-flags.queue-allocation-recommendations-mode")
    val deployments = mode("feature-flags.queue-allocation-deployments-mode")
    val modes = Set(recommendations, deployments)
    val enabled = modes.contains(QueueAllocationMode.Neutral)

    val neutralMaxKey = "crunch.queue-allocation.neutral.max-duration"
    val fallbackMaxKey = "crunch.queue-allocation.fallback.max-duration"
    val cancelKey = "crunch.queue-allocation.cancel-grace"
    val shutdownKey = "crunch.queue-allocation.shutdown-grace"

    val neutralMax = duration(neutralMaxKey)
    val fallbackMax = duration(fallbackMaxKey)
    val cancelGrace = duration(cancelKey)
    val shutdownGrace = duration(shutdownKey)

    requireDuration(neutralMaxKey, neutralMax, enabled)
    requireDuration(fallbackMaxKey, fallbackMax, enabled)
    requireDuration(cancelKey, cancelGrace, enabled)
    requireDuration(shutdownKey, shutdownGrace, enabled)
    val phaseTimeout = shutdownPhaseTimeout(config)
    require(
      !enabled || shutdownGrace.forall(grace => phaseTimeout.forall(grace <= _)),
      s"$shutdownKey must not exceed the coordinated-shutdown phase timeout"
    )

    val neutral = for {
      maxDuration <- neutralMax
      fallbackDuration <- fallbackMax
    } yield NeutralAllocationSettings(maxDuration, fallbackDuration)

    QueueAllocationSettings(recommendations, deployments, neutral, cancelGrace, shutdownGrace)
  }
}
