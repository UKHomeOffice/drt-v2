package drt.client.services.handlers

import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.components.FileUploadState
import drt.client.logger.log
import org.scalajs.dom
import org.scalajs.dom.FormData
import org.scalajs.dom.ext.AjaxException
import upickle.default._

import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class ResponseMessage(message: String)

object ResponseMessage {

  import upickle.default.{macroRW, ReadWriter => RW}

  implicit val rw: RW[ResponseMessage] = macroRW
}

class ForecastFileUploadHandler[M](modelRW: ModelRW[M, Pot[FileUploadState]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case ResetFileUpload() =>
      updated(Ready(FileUploadState(state = "", message = "")))


    case FileUploadStatus(fileUploadState: FileUploadState) =>
      updated(Ready(fileUploadState))


    case ForecastFileUploadAction(portCode: String, formData: FormData) =>

      val request = new dom.XMLHttpRequest()
      val promisedRequest = Promise[dom.XMLHttpRequest]()

      request.onreadystatechange = { (e: dom.Event) =>
        if (request.readyState.toInt == 4) {
          if ((request.status >= 200 && request.status < 300) || request.status == 304)
            promisedRequest.success(request)
          else
            promisedRequest.failure(AjaxException(request))
        }
      }

      request.open("POST", s"data/feed/forecast/$portCode")
      request.responseType = "text"
      request.timeout = 1000000
      request.withCredentials = false
      request.send(formData)

      val apiCallEffect = Effect(promisedRequest.future.map { res =>
        val rMessage = read[ResponseMessage](res.responseText)
        log.info(s"Uploading file response ${rMessage.message}")
        FileUploadStatus(FileUploadState(state = "uploaded", message = rMessage.message))
      }.recoverWith {
        case e =>
          log.error(s"failed to upload $e")
          Future(FileUploadStatus(FileUploadState(state = "error", message = e.getMessage)))
      })

      effectOnly(apiCallEffect)
  }


}
