package services

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


trait VirusScanServiceLike {
  def scan(fileName: String, filePath: String): Future[String]
}

case class VirusScanner(service: VirusScanServiceLike) {
  def fileIsOk(fileName: String, filePath: String): Boolean = {
    val futureResponse = service.scan(fileName, filePath)

    Await
      .result(futureResponse, 30 seconds)
      .contains("Everything ok : true")
  }
}
