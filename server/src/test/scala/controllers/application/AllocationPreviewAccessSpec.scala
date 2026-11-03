package controllers.application

import drt.shared.{ AllocationPreview, FeatureFlags }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ never, verify, when }
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.mvc.{ Headers, Session }
import play.api.test.{ FakeRequest, Helpers }
import play.api.test.Helpers._
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.AppEnvironment.{ AppEnvironment, OtherEnv, PreProdEnv, ProdEnv }
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{
  AccessOnlyPreprod,
  AccessOnlyProd,
  Debug,
  DesksAndQueuesView,
  Role,
  SuperAdmin
}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{ read, write }

import scala.concurrent.Await
import scala.concurrent.duration._

class AllocationPreviewAccessSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private val system = ActorSystem("allocation-preview-access-spec")
  private val airport = TestDefaults.airportConfig
  private val required: Set[Role] = Set(AccessOnlyPreprod, Debug, DesksAndQueuesView, airport.role)
  private val input =
    AllocationPreview.Request(T1, SDate("2026-10-30").toLocalDate)

  private def controller(environment: AppEnvironment, flag: Boolean, roles: Set[Role])
      : (DrtSystemInterface, QueueAllocationPreviewController) = {
    val ctrl = mock[DrtSystemInterface]
    val config = Configuration("feature-flags.enable-queue-allocation-preview" -> flag)
    val user = LoggedInUser("preview", "owner", "", roles)
    when(ctrl.system).thenReturn(system)
    when(ctrl.ec).thenReturn(system.dispatcher)
    when(ctrl.timeout).thenReturn(Timeout(5.seconds))
    when(ctrl.airportConfig).thenReturn(airport)
    when(ctrl.config).thenReturn(config)
    when(ctrl.env).thenReturn(environment)
    when(ctrl.params).thenReturn(MockDrtParameters())
    when(ctrl.getLoggedInUser(any[Configuration], any[Headers], any[Session])).thenReturn(user)
    ctrl -> new QueueAllocationPreviewController(Helpers.stubControllerComponents(), ctrl)
  }

  override protected def afterAll(): Unit =
    try Await.result(system.terminate(), 5.seconds)
    finally super.afterAll()

  "The advertised neutral capability and protected POST" should {
    val denied = Seq(
      ("flag off", PreProdEnv, false, required),
      ("production with all roles", ProdEnv, true, required + AccessOnlyProd + SuperAdmin),
      ("other environment", OtherEnv, true, required),
      ("missing preproduction role", PreProdEnv, true, required - AccessOnlyPreprod),
      ("missing Debug role", PreProdEnv, true, required - Debug),
      ("wrong airport", PreProdEnv, true, required - airport.role),
      ("missing view access", PreProdEnv, true, required - DesksAndQueuesView),
      ("SuperAdmin alone", PreProdEnv, true, Set[Role](SuperAdmin)),
      ("SuperAdmin cannot substitute Debug", PreProdEnv, true, required - Debug + SuperAdmin),
      ("SuperAdmin cannot substitute preproduction role", PreProdEnv, true, required - AccessOnlyPreprod + SuperAdmin)
    )
    denied.foreach { case (label, environment, flag, roles) =>
      s"deny $label without initialising or reading the application preview service" in {
        val (ctrl, preview) = controller(environment, flag, roles)
        val result = new FeatureFlagsController(Helpers.stubControllerComponents(), ctrl).getFeatureFlags
          .apply(FakeRequest())
        read[FeatureFlags](contentAsString(result)).enableQueueAllocationPreview mustBe false
        status(preview.calculate.apply(FakeRequest().withBody(write(input)))) mustBe FORBIDDEN
        status(preview.calculate.apply(FakeRequest().withBody("invalid"))) mustBe FORBIDDEN
        verify(ctrl, never()).applicationService
      }
    }

    "advertise true only for enabled preproduction with all required permissions, including both environment roles" in {
      Seq(required, required + AccessOnlyProd, required + SuperAdmin).foreach { roles =>
        val (ctrl, _) = controller(PreProdEnv, true, roles)
        val result = new FeatureFlagsController(Helpers.stubControllerComponents(), ctrl).getFeatureFlags
          .apply(FakeRequest())
        read[FeatureFlags](contentAsString(result)).enableQueueAllocationPreview mustBe true
        verify(ctrl, never()).applicationService
      }
    }
  }
}
