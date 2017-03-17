package drt.chroma

import akka.stream._
import akka.stream.stage._

import scala.collection.immutable.Seq

object DiffingStage {
  def DiffLists[T]() = new DiffingStage[Seq[T]](Nil)(diffLists)

  def diffLists[T](a: Seq[T], b: Seq[T]): Seq[T] = {
    val aSet = a.toSet
    val bSet = b.toSet
    (bSet -- aSet).toList
  }
}

final class DiffingStage[T](initialValue: T)(diff: (T, T) => T) extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("DiffingStage.in")
  val out = Outlet[T]("DiffingStage.out")

  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var currentValue: T = initialValue
    private var latestDiff: T = initialValue

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        doADiff()
        push(out, latestDiff)
      }

      override def onPull(): Unit = {
        pull(in)
        doADiff()
      }
    })

    def doADiff(): Unit = {
      if (isAvailable(in)) {
        val newValue = grab(in)
        val diff1: T = diff(currentValue, newValue)
        latestDiff = diff1
        currentValue = newValue
      }
    }
  }
}

