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

      val tReq = new dom.XMLHttpRequest()
      val tPromise = Promise[dom.XMLHttpRequest]()

      tReq.onreadystatechange = { (e: dom.Event) =>
        if (tReq.readyState.toInt == 4) {
          if ((tReq.status >= 200 && tReq.status < 300) || tReq.status == 304)
            tPromise.success(tReq)
          else
            tPromise.failure(AjaxException(tReq))
        }
      }

      tReq.open("POST", s"data/feed/forecast/lhr")
      tReq.responseType = "text"
      tReq.timeout = 1000000
      tReq.withCredentials = false
      tReq.send(formData)

      val apiCallEffect = Effect(tPromise.future.map { res =>
        val rMessage = read[ResponseMessage](res.responseText)
        log.info(s"Uploading file response ${rMessage.message}")
        FileUploadStatus(FileUploadState(state = "uploaded", message = rMessage.message))
      }.recoverWith {
        case e =>
          log.error(s"fail to upload file to data/feed/fileUpload/$portCode / ${e.getMessage}")
          Future(FileUploadStatus(FileUploadState(state = "error", message = e.getMessage)))
      })

      effectOnly(apiCallEffect)
  }


}
