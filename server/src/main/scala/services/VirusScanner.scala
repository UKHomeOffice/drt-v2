package services

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait VirusScanServiceLike {
  def scan(fileName: String, filePath: String): Future[String]
}

case class VirusScanner(service: VirusScanServiceLike) {
  def fileIsOk(fileName: String, filePath: String): Future[Boolean] = {
    val futureResponse = service.scan(fileName, filePath)

    futureResponse.map(_.contains("Everything ok : true"))
  }
}
