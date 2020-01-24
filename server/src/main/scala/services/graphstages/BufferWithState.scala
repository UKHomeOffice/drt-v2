package services.graphstages

import akka.event.Logging
import akka.stream._
import akka.stream.stage._
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.collection.mutable

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

class SortedSetBuffer(initialValues: Iterable[Long]) extends BufferImpl[Long] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val values: mutable.SortedSet[Long] = mutable.SortedSet[Long]() ++ initialValues
  val lastSent: mutable.Map[Long, Long] = mutable.Map()

  override def used: Int = values.size

  override def isEmpty: Boolean = values.isEmpty

  override def nonEmpty: Boolean = !isEmpty

  override def enqueue(elem: Long): Unit = {
    log.debug(s"Adding ${SDate(elem).toISOString()} to ${values.map(ms => SDate(ms).toISOString).mkString(", ")}")
    values += elem
  }

  override def dequeue(): Long = {
    val nextElement = values.head
    values -= nextElement
    log.debug(s"Removed ${SDate(nextElement).toISOString} leaving ${values.map(ms => SDate(ms).toISOString).mkString(", ")}")
    nextElement
  }

  override def clear(): Unit = values.clear()
}

case class Buffer(initialValues: Iterable[Long]) extends GraphStage[FlowShape[Iterable[Long], Long]] {
  val in: Inlet[Iterable[Long]] = Inlet[Iterable[Long]](Logging.simpleName(this) + ".in")
  val out: Outlet[Long] = Outlet[Long](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      private val buffer: SortedSetBuffer = new SortedSetBuffer(initialValues)

      override def onPush(): Unit = {
        val elems = grab(in)

        elems.foreach(elem => buffer.enqueue(elem))

        pushAndPullIfPossible()
      }

      override def onPull(): Unit = {
        pushAndPullIfPossible()
      }

      def pushAndPullIfPossible(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) {
          val nextElement: MillisSinceEpoch = buffer.dequeue()
          push(out, nextElement)
        }
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
