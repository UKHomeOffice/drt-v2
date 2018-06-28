package drt.chroma

import akka.stream._
import akka.stream.stage._
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, FeedResponse}

object DiffingStage {
  def DiffLists = new ArrivalsDiffingStage(diffLists)

  def diffLists[T](a: Seq[T], b: Seq[T]): Seq[T] = {
    val aSet = a.toSet
    val bSet = b.toSet

    (bSet -- aSet).toList
  }
}

//final class DiffingStage[T](initialValue: T)(diff: (T, T) => T) extends GraphStage[FlowShape[T, T]] {
//  val in = Inlet[T]("DiffingStage.in")
//  val out = Outlet[T]("DiffingStage.out")
//
//  override val shape = FlowShape.of(in, out)
//
//  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
//    private var currentValue: T = initialValue
//    private var maybeLatestDiff: T = initialValue
//
//    setHandlers(in, out, new InHandler with OutHandler {
//      override def onPush(): Unit = {
//        doADiff()
//        push(out, maybeLatestDiff)
//      }
//
//      override def onPull(): Unit = {
//        pull(in)
//        doADiff()
//      }
//    })
//
//    def doADiff(): Unit = {
//      if (isAvailable(in)) {
//        val newValue = grab(in)
//        val diff1: T = diff(currentValue, newValue)
//        maybeLatestDiff = diff1
//        currentValue = newValue
//      }
//    }
//  }
//}

final class ArrivalsDiffingStage(diff: (Seq[Arrival], Seq[Arrival]) => Seq[Arrival]) extends GraphStage[FlowShape[FeedResponse, FeedResponse]] {
  val in: Inlet[FeedResponse] = Inlet[FeedResponse]("DiffingStage.in")
  val out: Outlet[FeedResponse] = Outlet[FeedResponse]("DiffingStage.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  override val shape: FlowShape[FeedResponse, FeedResponse] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var currentValue: Seq[Arrival] = Seq()
    private var maybeLatestDiff: Option[FeedResponse] = None

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {
        grabAndDiff()
        pushIfPossible()
        pullIfPossible()
      }

      override def onPull(): Unit = {
        pushIfPossible()
        pullIfPossible()
      }
    })

    def pullIfPossible(): Unit = {
      if (!hasBeenPulled(in)) pull(in)
    }

    def pushIfPossible(): Unit = {
      maybeLatestDiff.foreach(latestDiff => {
        push(out, latestDiff)
        maybeLatestDiff = None
      })
    }

    def grabAndDiff(): Unit = {
      if (isAvailable(in)) {
        grab(in) match {
          case ArrivalsFeedSuccess(Flights(arrivals), createdAt) =>
            val newDiff = diff(currentValue, arrivals)
            log.info(s"${newDiff.length} updated arrivals from diff")
            if (newDiff.nonEmpty) {
              maybeLatestDiff = maybeLatestDiff match {
                case Some(ArrivalsFeedSuccess(Flights(existingArrivalsDiff), _)) =>
                  val updatedDiff = newDiff
                    .foldLeft(existingArrivalsDiff.map(a => (a.uniqueId, a)).toMap) {
                      case (soFar, newArrivalUpdate) => soFar.updated(newArrivalUpdate.uniqueId, newArrivalUpdate)
                    }
                    .values
                    .toSeq
                  log.info(s"Merged ${newDiff.length} updated arrivals with existing diff")
                  Option(ArrivalsFeedSuccess(Flights(updatedDiff), createdAt))
                case _ =>
                  Option(ArrivalsFeedSuccess(Flights(newDiff), createdAt))
              }
              currentValue = arrivals
            }
          case aff@ArrivalsFeedFailure(_, _) =>
            log.info("Feed failure. No updated arrivals")
            Option(aff)
        }
      }
    }
  }
}

