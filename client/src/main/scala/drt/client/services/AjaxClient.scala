package drt.client.services

import java.nio.ByteBuffer

import boopickle.Default._
import org.scalajs.dom
import org.scalajs.dom.ext.Ajax.InputData
import drt.client.SPAMain

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.typedarray._

object AjaxClient extends autowire.Client[ByteBuffer, Pickler, Pickler] {

//  var doCallImpl: (AjaxClient.Request) => Future[ByteBuffer] = doCallProd _
  
  override def doCall(req: Request): Future[ByteBuffer] = {
//    println(s"doCall $req")
    dom.ext.Ajax.post(
      url = SPAMain.pathToThisApp + "/api/" + req.path.mkString("/"),
      data = Pickle.intoBytes(req.args),
      responseType = "arraybuffer",
      headers = Map("Content-Type" -> "application/octet-stream")
    ).map(r => TypedArrayBuffer.wrap(r.response.asInstanceOf[ArrayBuffer]))
  }


  def monkeyPatchDoCallImpl(impl: (AjaxClient.Request) => Future[ByteBuffer]) = {
    println(s"would mock - but disabled mocking ajax impl")
//    doCallImpl = impl
  }
  override def read[Result: Pickler](p: ByteBuffer) = Unpickle[Result].fromBytes(p)

  override def write[Result: Pickler](r: Result) = Pickle.intoBytes(r)
}
