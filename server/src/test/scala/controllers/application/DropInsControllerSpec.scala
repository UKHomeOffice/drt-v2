package controllers.application

import akka.actor.ActorSystem
import akka.stream.Materializer
import email.GovNotifyEmail
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface

import scala.concurrent.ExecutionContext

class DropInsControllerSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)
  implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  val controller: DropInsController = dropInSessionsController
  "DropInsController" should {
    "get published drop-ins" in {

      val result = controller.dropIns().apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""[{"id":[1],"title":"test","startTime":1696687258000,"endTime":1696692658000,"isPublished":true,"meetingLink":[],"lastUpdatedAt":1695910303210}]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "get drop-ins in registration" in {

      val result = controller.getDropInRegistrations().apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""[{"email":"someone@test.com","dropInId":1,"registeredAt":1695910303210,"emailSentAt":[1695910303210]}]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "create drop-ins registration" in {

      val result = controller.createDropInRegistration().apply(FakeRequest().withTextBody(""""1"""")
        .withHeaders("X-Auth-Email" -> "someone@test.com", "X-Auth-Roles" -> "border-force-staff,TEST"))

      status(result) mustBe OK

      contentAsString(result) must include("Successfully registered drop-ins")
    }
  }

  private def dropInSessionsController = {
    val drtSystemInterface: DrtSystemInterface = new TestDrtModule().provideDrtSystemInterface

    val govNotify = mock[GovNotifyEmail]

    new DropInsController(Helpers.stubControllerComponents(), drtSystemInterface, govNotify)
  }
}
