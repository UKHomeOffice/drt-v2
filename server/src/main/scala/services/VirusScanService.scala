package services

import java.io.File

import dispatch.{Http, Req, url}
import org.asynchttpclient.request.body.multipart.FilePart

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class VirusScanService(serviceUrl: String) extends VirusScanServiceLike {
  def scan(fileName: String, filePath: String): Future[String] = {
    val file = new File(filePath)

    val filePart = new FilePart(fileName, file, "application/octet-stream")

    val urlToCall: Req = Req(_ => url(serviceUrl).toRequestBuilder.addBodyPart(filePart).setMethod("POST"))
    Http.default(urlToCall)
      .map(res => res.getResponseBody)
  }
}
