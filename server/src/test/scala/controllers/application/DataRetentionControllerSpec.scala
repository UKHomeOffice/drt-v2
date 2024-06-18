package controllers.application

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.ExecutionContext

class DataRetentionControllerSpec extends PlaySpec {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test-1")
  implicit val mat: Materializer = Materializer(system)
  implicit val ec: ExecutionContext = system.dispatcher

  "purge" should {
    "Have status Ok, and confirm the date range being deleted when the date is outside the retention period" in {
      val controller = dataRetentionController

      val result = controller.purge("2019-05-21").apply(FakeRequest().withHeaders("X-Forwarded-Groups" -> "super-admin,TEST"))

      status(result) mustBe OK

      val resultExpected = s"""Deleting data from 2019-05-21 to 2019-05-21""".stripMargin

      contentAsString(result) must ===(resultExpected)
    }
    "Have status BadRequest, and confirm the date range being deleted when the date is within the retention period" in {
      val controller = dataRetentionController

      val result = controller.purge("2019-05-22").apply(FakeRequest().withHeaders("X-Forwarded-Groups" -> "super-admin,TEST"))

      status(result) mustBe BAD_REQUEST

      val resultExpected = s"""Cannot purge data from 2019-05-22 as it is within the retention period (2019-05-21 onwards)""".stripMargin

      contentAsString(result) must ===(resultExpected)
    }
  }

  private def dataRetentionController = {
    val module = new TestDrtModule() {
      override val now: () => SDateLike = () => SDate("2024-05-20")
    }

    new DataRetentionController(Helpers.stubControllerComponents(), module.provideDrtSystemInterface)
  }
}
