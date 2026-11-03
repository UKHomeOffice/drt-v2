package services.crunch.deskrecs

import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

import scala.concurrent.duration.DurationInt

class QueueAllocationSettingsSpec extends AnyWordSpec with Matchers {
  private val budgets = """
    crunch.queue-allocation {
      neutral.max-duration = 3 seconds
      fallback.max-duration = 4 seconds
      cancel-grace = 100 milliseconds
      shutdown-grace = 1 second
    }
  """

  private def read(text: String): QueueAllocationSettings =
    QueueAllocationSettings.fromConfiguration(Configuration(ConfigFactory.parseString(text).resolve()))

  "Queue allocation settings" should {
    "default both calculation types to legacy without requiring execution budgets" in {
      val settings = read("")
      settings shouldBe QueueAllocationSettings.legacy
      settings.enabled shouldBe false
      settings.mode(QueueAllocationKind.Recommendations) shouldBe QueueAllocationMode.Legacy
      settings.mode(QueueAllocationKind.Deployments) shouldBe QueueAllocationMode.Legacy
    }

    "parse independent modes and explicit execution budgets" in {
      val settings = read(budgets + """
        feature-flags.queue-allocation-recommendations-mode = legacy
        feature-flags.queue-allocation-deployments-mode = neutral
      """)
      settings.recommendations shouldBe QueueAllocationMode.Legacy
      settings.deployments shouldBe QueueAllocationMode.Neutral
      settings.neutral shouldBe Some(NeutralAllocationSettings(3.seconds, 4.seconds))
      settings.cancelGrace shouldBe Some(100.millis)
      settings.enabled shouldBe true
    }

    "reject invalid mode values rather than using legacy as a fallback" in {
      Seq("shadow", "Shadow", "balanced", "unknown", "\" shadow\"", "\"\"").foreach { mode =>
        intercept[IllegalArgumentException] {
          read(budgets + s"\nfeature-flags.queue-allocation-recommendations-mode = $mode")
        }
      }
    }

    "require only the budgets needed by enabled modes" in {
      val neutralOnly = """
        feature-flags.queue-allocation-deployments-mode = neutral
        crunch.queue-allocation {
          neutral.max-duration = 2 seconds
          fallback.max-duration = 1 second
          cancel-grace = 100 milliseconds
          shutdown-grace = 1 second
        }
      """
      read(neutralOnly).recommendations shouldBe QueueAllocationMode.Legacy
      Seq("neutral.max-duration", "fallback.max-duration", "cancel-grace", "shutdown-grace").foreach { key =>
        val config = ConfigFactory.parseString(neutralOnly).withoutPath(s"crunch.queue-allocation.$key")
        intercept[IllegalArgumentException] {
          QueueAllocationSettings.fromConfiguration(Configuration(config))
        }
      }
    }

    "reject malformed, zero and negative explicitly supplied budgets even in legacy mode" in {
      val paths = Seq(
        "neutral.max-duration",
        "fallback.max-duration",
        "cancel-grace",
        "shutdown-grace"
      )
      for {
        path <- paths
        duration <- Seq("0 seconds", "-1 second", "\"not-a-duration\"")
      } {
        intercept[Exception] {
          read(s"crunch.queue-allocation.$path = $duration")
        }
      }
    }

    "validate shutdown grace against the actual coordinated-shutdown phase" in {
      intercept[IllegalArgumentException] {
        read(budgets + """
          feature-flags.queue-allocation-recommendations-mode = neutral
          pekko.coordinated-shutdown.default-phase-timeout = 100 milliseconds
        """)
      }
      read(budgets + """
        feature-flags.queue-allocation-recommendations-mode = neutral
        pekko.coordinated-shutdown.default-phase-timeout = 100 milliseconds
        pekko.coordinated-shutdown.phases.before-service-unbind.timeout = 2 seconds
      """).shutdownGrace shouldBe Some(1.second)
    }

    "load the checked-in mode defaults and optional environment override declarations" in {
      val resources = ConfigFactory.parseResources("config/feature-flags.conf")
        .withFallback(ConfigFactory.parseResources("config/crunch.conf"))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
      val settings = QueueAllocationSettings.fromConfiguration(Configuration(resources))
      settings shouldBe QueueAllocationSettings.legacy
    }
  }
}
