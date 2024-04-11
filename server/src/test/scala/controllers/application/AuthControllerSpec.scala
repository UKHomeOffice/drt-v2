package controllers.application

import drt.http.ProdSendAndReceive
import drt.shared.KeyCloakApi.KeyCloakUser
import drt.users.KeyCloakClient
import module.DRTModule
import org.mockito.Mockito.{times, verify, when}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{Action, AnyContent, Headers}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.auth.Roles.{ManageUsers, Role}

import scala.concurrent.Future

class AuthControllerSpec extends PlaySpec with MockitoSugar {

  "AuthController" should {

    val module = new DRTModule()

    val drtSystemInterface = module.provideDrtSystemInterface

    "get loggedIn User" in {

      val controller: AuthController = new AuthController(Helpers.stubControllerComponents(), drtSystemInterface) {
        override def getLoggedInUser: Action[AnyContent] = super.getLoggedInUser()
      }

      val result = controller.getLoggedInUser().apply(FakeRequest()
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Username" -> "test",
          "X-Auth-Userid" -> "test",
          "X-Auth-Roles" -> "TEST")
      )

      status(result) mustBe OK

      val resultExpected =
        s"""{"userName":"test","id":"test","email":"test@test.com","roles":["TEST"]}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "get user details" in {

      val keyCloakClient = mock[KeyCloakClient with ProdSendAndReceive]

      when(keyCloakClient.getUsersForEmail("test@test.com"))
        .thenReturn(Future.successful(Some(KeyCloakUser("test", "test", false, false, "test", "test", "test@test.com"))))

      val controller: AuthController = new AuthController(Helpers.stubControllerComponents(), drtSystemInterface) {
        override def getLoggedInUser: Action[AnyContent] = super.getLoggedInUser()

        override def userDetails(email: String): Action[AnyContent] = super.userDetails(email)

        override def keyCloakClientWithHeader(headers: Headers): KeyCloakClient with ProdSendAndReceive = keyCloakClient

      }

      val result = controller.userDetails("test@test.com").apply(FakeRequest()
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Username" -> "test",
          "X-Auth-Userid" -> "test",
          "X-Auth-Roles" -> s"TEST,${ManageUsers.name}")
      )

      status(result) mustBe OK

      verify(keyCloakClient, times(1)).getUsersForEmail("test@test.com")
      val resultExpected =
        s"""{"id":"test","username":"test","enabled":false,"emailVerified":false,"firstName":"test","lastName":"test","email":"test@test.com"}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "authByRole" in {

      val controller = new AuthController(Helpers.stubControllerComponents(), drtSystemInterface) {
        override def getLoggedInUser: Action[AnyContent] = super.getLoggedInUser()

        override def userDetails(email: String): Action[AnyContent] = super.userDetails(email)

        override def authByRole[A](allowedRole: Role)(action: Action[A]): Action[A] = super.authByRole(allowedRole)(action)

        val action: Action[AnyContent] = Action.async { _ =>
          Future.successful(Ok("Success!"))
        }

      }

      val result = controller.authByRole(ManageUsers)(controller.action).apply(FakeRequest()
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Username" -> "test",
          "X-Auth-Userid" -> "test",
          "X-Auth-Roles" -> s"TEST,${ManageUsers.name}")
      )

      status(result) mustBe OK

      val resultExpected = "Success!"
      contentAsString(result) must include(resultExpected)
    }

    "trackUser" in {

      val controller: AuthController = new AuthController(Helpers.stubControllerComponents(), drtSystemInterface) {
        override def trackUser: Action[AnyContent] = super.trackUser()
      }

      val result = controller.trackUser().apply(FakeRequest()
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Username" -> "test",
          "X-Auth-Userid" -> "test",
          "X-Auth-Roles" -> s"TEST,${ManageUsers.name}")
      )

      status(result) mustBe OK

      val resultExpected = "User-tracked"
      contentAsString(result) must include(resultExpected)
    }

  }
}
