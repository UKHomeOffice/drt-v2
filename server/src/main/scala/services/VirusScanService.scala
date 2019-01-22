package services

import java.nio.file.Paths

import akka.stream.scaladsl.{FileIO, Source}
import play.api.libs.ws.WSClient
import play.api.mvc.MultipartFormData.{DataPart, FilePart}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class VirusScanService(serviceUrl: String, wsClient: WSClient) extends VirusScanServiceLike {
  def scan(fileName: String, filePath: String): Future[String] = {
    val filePart = FilePart("file", fileName, Option("application/octet-stream"), FileIO.fromPath(Paths.get(filePath)))

    wsClient
      .url(serviceUrl)
      .post(Source(filePart :: DataPart("key", "value") :: List()))
      .map(_.body)
  }
}