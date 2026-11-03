package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

import scala.concurrent.duration._

class AllocationPreviewSettingsSpec extends AnyWordSpec with Matchers {
  "Neutral calculation settings" should {
    "supply bounded defaults independently of the live legacy modes" in {
      AllocationPreviewSettings.fromConfiguration(Configuration.empty) shouldBe
        AllocationPreviewSettings(2.minutes, 1.second, 3.seconds, 250000)
      QueueAllocationSettings.fromConfiguration(Configuration.empty) shouldBe QueueAllocationSettings.legacy
    }

    "read each configured limit" in {
      AllocationPreviewSettings.fromConfiguration(Configuration(
        "crunch.queue-allocation.preview.budget" -> "1 minute",
        "crunch.queue-allocation.preview.cancel-grace" -> "100 milliseconds",
        "crunch.queue-allocation.preview.shutdown-grace" -> "2 seconds",
        "crunch.queue-allocation.preview.max-passengers" -> 100
      )) shouldBe AllocationPreviewSettings(1.minute, 100.millis, 2.seconds, 100)
    }

    "reject nonpositive and excessive values for every limit" in {
      Seq[() => AllocationPreviewSettings](
        () => AllocationPreviewSettings(budget = Duration.Zero),
        () => AllocationPreviewSettings(budget = 11.minutes),
        () => AllocationPreviewSettings(cancelGrace = Duration.Zero),
        () => AllocationPreviewSettings(cancelGrace = 6.seconds),
        () => AllocationPreviewSettings(shutdownGrace = Duration.Zero),
        () => AllocationPreviewSettings(shutdownGrace = 6.seconds),
        () => AllocationPreviewSettings(maxPassengers = 0),
        () => AllocationPreviewSettings(maxPassengers = 250001)
      ).foreach(factory => intercept[IllegalArgumentException](factory()))
    }

    "respect both the explicit shutdown phase timeout and its fallback" in {
      Seq(
        "pekko.coordinated-shutdown.phases.before-service-unbind.timeout",
        "pekko.coordinated-shutdown.default-phase-timeout"
      ).foreach { key =>
        intercept[IllegalArgumentException] {
          AllocationPreviewSettings.fromConfiguration(Configuration(key -> "1 second"))
        }
        AllocationPreviewSettings.fromConfiguration(Configuration(key -> "3 seconds")) shouldBe
          AllocationPreviewSettings()
      }
    }

    "fit the calculation and cancellation budget inside a configured finite HTTP idle timeout" in {
      Seq("180 seconds", "infinite").foreach { timeout =>
        AllocationPreviewSettings.fromConfiguration(Configuration(
          "play.server.http.idleTimeout" -> timeout
        )) shouldBe AllocationPreviewSettings()
      }
      Seq("75 seconds", "121 seconds", "0 seconds").foreach { timeout =>
        val error = intercept[IllegalArgumentException] {
          AllocationPreviewSettings.fromConfiguration(Configuration("play.server.http.idleTimeout" -> timeout))
        }
        error.getMessage should include("play.server.http.idleTimeout")
      }
      intercept[IllegalArgumentException] {
        AllocationPreviewSettings.fromConfiguration(Configuration(
          "play.server.http.idleTimeout" -> "180 seconds",
          "crunch.queue-allocation.preview.budget" -> "3 minutes"
        ))
      }
    }
  }
}
