package services.crunch.deskrecs

import play.api.Configuration

import scala.concurrent.duration._

case class AllocationPreviewSettings(
    budget: FiniteDuration = 2.minutes,
    cancelGrace: FiniteDuration = 1.second,
    shutdownGrace: FiniteDuration = 3.seconds,
    maxPassengers: Int = 250000
) {
  require(budget > Duration.Zero && budget <= 10.minutes, "Invalid preview calculation budget")
  require(cancelGrace > Duration.Zero && cancelGrace <= 5.seconds, "Invalid preview cancellation grace")
  require(shutdownGrace > Duration.Zero && shutdownGrace <= 5.seconds, "Invalid preview shutdown grace")
  require(maxPassengers > 0 && maxPassengers <= 250000, "Invalid preview passenger entry limit")
}

object AllocationPreviewSettings {
  def fromConfiguration(config: Configuration): AllocationPreviewSettings = {
    val defaults = AllocationPreviewSettings()
    val prefix = "crunch.queue-allocation.preview."
    val settings = AllocationPreviewSettings(
      config.getOptional[FiniteDuration](prefix + "budget").getOrElse(defaults.budget),
      config.getOptional[FiniteDuration](prefix + "cancel-grace").getOrElse(defaults.cancelGrace),
      config.getOptional[FiniteDuration](prefix + "shutdown-grace").getOrElse(defaults.shutdownGrace),
      config.getOptional[Int](prefix + "max-passengers").getOrElse(defaults.maxPassengers)
    )
    require(
      QueueAllocationSettings.shutdownPhaseTimeout(config).forall(settings.shutdownGrace <= _),
      "Preview shutdown grace exceeds shutdown phase"
    )
    val httpIdle = config.getOptional[Duration]("play.server.http.idleTimeout")
    require(
      httpIdle.forall(timeout => !timeout.isFinite || settings.budget + settings.cancelGrace < timeout),
      "Neutral calculation budget and cancellation grace must fit play.server.http.idleTimeout; " +
        "set the server timeout at startup (the local launcher uses 180s)"
    )
    settings
  }
}
