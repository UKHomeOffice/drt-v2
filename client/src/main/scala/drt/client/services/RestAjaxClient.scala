package drt.client.services

import java.nio.ByteBuffer

import boopickle.Default._
import drt.client.SPAMain
import drt.shared.{FeedStatus, FeedStatusFailure, FeedStatusSuccess}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.typedarray._

object RestAjaxClient extends autowire.Client[ByteBuffer, Pickler, Pickler] {

  override def doCall(req: Request): Future[ByteBuffer] = {
    dom.ext.Ajax.post(
      url = SPAMain.pathToThisApp + "/data/" + req.path.mkString("/"),
      data = Pickle.intoBytes(req.args),
      responseType = "arraybuffer",
      headers = Map("Content-Type" -> "application/octet-stream")
    ).map(r => TypedArrayBuffer.wrap(r.response.asInstanceOf[ArrayBuffer]))
  }

  def monkeyPatchDoCallImpl(impl: (RestAjaxClient.Request) => Future[ByteBuffer]) = {
    println(s"would mock - but disabled mocking ajax impl")
  }

  override def read[Result: Pickler](p: ByteBuffer) = {
    Unpickle[Result].fromBytes(p)
  }

  override def write[Result: Pickler](r: Result) = {
    Pickle.intoBytes(r)
  }
}
