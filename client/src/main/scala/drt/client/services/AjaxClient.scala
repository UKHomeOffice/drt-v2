package drt.client.services

import java.nio.ByteBuffer

import boopickle.CompositePickler
import boopickle.Default._
import drt.client.SPAMain
import drt.shared._
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.typedarray._

object AjaxClient extends autowire.Client[ByteBuffer, Pickler, Pickler] {
  implicit val feedStatusPickler: CompositePickler[FeedStatus] = compositePickler[FeedStatus].
    addConcreteType[FeedStatusSuccess].
    addConcreteType[FeedStatusFailure]

  implicit val sDatePickler: CompositePickler[SDateLike] = compositePickler[SDateLike]
  implicit val staffAssignmentPickler: CompositePickler[StaffAssignment] = compositePickler[StaffAssignment]
  implicit val staffAssignmentsPickler: CompositePickler[StaffAssignments] = compositePickler[StaffAssignments].addConcreteType[FixedPointAssignments].addConcreteType[ShiftAssignments]

  override def doCall(req: Request): Future[ByteBuffer] = {
    dom.ext.Ajax.post(
      url = SPAMain.absoluteUrl("api/" + req.path.mkString("/")),
      data = Pickle.intoBytes(req.args),
      responseType = "arraybuffer",
      headers = Map("Content-Type" -> "application/octet-stream")
    ).map(r => TypedArrayBuffer.wrap(r.response.asInstanceOf[ArrayBuffer]))
  }

  override def read[Result: Pickler](p: ByteBuffer): Result = {
    Unpickle[Result].fromBytes(p)
  }

  override def write[Result: Pickler](r: Result): ByteBuffer = {
    Pickle.intoBytes(r)
  }
}
