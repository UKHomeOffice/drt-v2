package services

import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class VirusScanServiceMock(response: String) extends VirusScanServiceLike {
  override def scan(fileName: String, filePath: String): Future[String] = Future(response)
}

class VirusScanServiceSpec extends Specification {
  "When asking the virus scanner if a file is free of viruses" >> {
    "Given a virus scanner with mocked bad response " +
    "Then it should return false " >> {
      val mockService = VirusScanServiceMock("bad response")
      val scanner = VirusScanner(mockService)

      val result = Await.result(scanner.fileIsOk("filename", "filepath"), 1 second)

      val expected = false

      result === expected
    }

    "Given a virus scanner with mocked good response " +
    "Then it should return true " >> {
      val mockService = VirusScanServiceMock("Everything ok : true")
      val scanner = VirusScanner(mockService)

      val result = Await.result(scanner.fileIsOk("filename", "filepath"), 1 second)

      val expected = true

      result === expected
    }
  }
}
