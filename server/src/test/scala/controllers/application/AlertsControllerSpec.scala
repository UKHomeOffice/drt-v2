package controllers.application

import akka.http.scaladsl.model.StatusCodes.OK
import drt.shared.Alert
import module.DRTModule
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

class AlertsControllerSpec extends PlaySpec with MockitoSugar {

  "AlertsController" should {

    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
    }

    val drtSystemInterface = module.provideDrtSystemInterface

    "get alerts created after the given time" in {

      val controller = new AlertsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getAlerts(System.currentTimeMillis() - 10000).apply(FakeRequest())

      status(result) mustBe OK.intValue

      val resultExpected = "[]"
      contentAsString(result) must include(resultExpected)
    }

    "add an alert" in {

      val controller = new AlertsController(Helpers.stubControllerComponents(), drtSystemInterface)
      val alert: Alert = Alert("title", "message", "alertClass", System.currentTimeMillis() + 100000, System.currentTimeMillis())
      import Alert.rw
      val jsonString: String = upickle.default.write(alert)
      val resultAdd = controller.addAlert().apply(FakeRequest(POST, "/alerts")
        .withTextBody(jsonString)
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Roles" -> "create-alerts,TEST",
          "Content-Type" -> "application/json"
        )
      )
      status(resultAdd) mustBe 202

      val result = controller.getAlerts(System.currentTimeMillis() - 10000).apply(FakeRequest())

      status(result) mustBe OK.intValue

      val resultString = contentAsString(result)
      val alertResult = upickle.default.read[Seq[Alert]](resultString)

      alertResult.head mustEqual alert.copy(createdAt = alertResult.head.createdAt)
    }

    "delete all alerts" in {
      val controller = new AlertsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.deleteAlerts().apply(FakeRequest(POST, "/alerts/delete")
        .withHeaders("X-Auth-Email" -> "test@test.com",
          "X-Auth-Roles" -> "create-alerts,TEST",
          "Content-Type" -> "application/json"
        ))

      status(result) mustBe OK.intValue

      val resultAfterDelete = controller.getAlerts(System.currentTimeMillis() - 10000).apply(FakeRequest())

      status(resultAfterDelete) mustBe OK.intValue

      val resultExpected = "List()"

      contentAsString(result) must include(resultExpected)

    }

  }

}
