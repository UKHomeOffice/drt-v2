package services.graphstages

import akka.event.Logging
import akka.stream.stage._
import akka.stream._
import services.SDate

import scala.collection.{SortedSet, mutable}

abstract class SimpleLinearGraphStage[T] extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T](Logging.simpleName(this) + ".in")
  val out: Outlet[T] = Outlet[T](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)
}

trait BufferImpl[T] {
  def used: Int

  def isEmpty: Boolean

  def nonEmpty: Boolean

  def enqueue(elem: T): Unit

  def dequeue(): T

  def clear(): Unit
}

class SortedSetBuffer() extends BufferImpl[Long] {
  val values: mutable.SortedSet[Long] = mutable.SortedSet[Long]()

  override def used: Int = values.size

  override def isEmpty: Boolean = values.isEmpty

  override def nonEmpty: Boolean = !isEmpty

  override def enqueue(elem: Long): Unit = {
    values += elem
  }

  override def dequeue(): Long = {
    val head = values.head
    values -= head
    head
  }

  override def clear(): Unit = values.clear()
}

case class Buffer() extends GraphStage[FlowShape[List[Long], Long]] {
  val in: Inlet[List[Long]] = Inlet[List[Long]](Logging.simpleName(this) + ".in")
  val out: Outlet[Long] = Outlet[Long](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      private val buffer: SortedSetBuffer = new SortedSetBuffer()

      override def onPush(): Unit = {
        val elems = grab(in)

        elems.foreach(elem => buffer.enqueue(elem))

        pushAndPullIfPossible()
      }

      override def onPull(): Unit = {
        pushAndPullIfPossible()
      }

      def pushAndPullIfPossible(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) push(out, buffer.dequeue())
        if (isClosed(in)) {
          if (buffer.isEmpty) completeStage()
        } else if (!hasBeenPulled(in)) {
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (buffer.isEmpty) completeStage()
      }

      setHandlers(in, out, this)
    }
}
