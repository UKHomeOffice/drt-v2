package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.prediction.{SparkSplitsPredictor, SplitsPredictorFactoryLike}


abstract class SplitsPredictorBase extends GraphStage[FlowShape[Seq[Arrival], Seq[(Arrival, Option[Splits])]]]

class DummySplitsPredictor() extends SplitsPredictorBase {
  val in: Inlet[Seq[Arrival]] = Inlet[Seq[Arrival]]("SplitsPredictor.in")
  val out: Outlet[Seq[(Arrival, Option[Splits])]] = Outlet[Seq[(Arrival, Option[Splits])]]("SplitsPredictor.out")

  val shape: FlowShape[Seq[Arrival], Seq[(Arrival, Option[Splits])]] = FlowShape.of(in, out)

  val log = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var haveGrabbed: Boolean = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val start = SDate.now()
          log.info(s"Dummy predictor onPush() - grabbing arrivals")
          grab(in)
          haveGrabbed = true
          if (!hasBeenPulled(in)) {
            log.info(s"Dummy predictor pull(in)")
            pull(in)
          }
          log.info(s"in Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val start = SDate.now()
          log.info(s"Dummy predictor onPull()")
          if (haveGrabbed && isAvailable(out)) {
            log.info(s"Dummy predictor pushing empty results")
            push(out, Seq[(Arrival, Option[Splits])]())
            haveGrabbed = false
          }
          if (!hasBeenPulled(in)) {
            log.info(s"Dummy predictor pull(in)")
            pull(in)
          }
          log.info(s"out Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
        }
      })
    }
}

class SplitsPredictorStage(splitsPredictorFactory: SplitsPredictorFactoryLike) extends SplitsPredictorBase {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val in: Inlet[Seq[Arrival]] = Inlet[Seq[Arrival]]("SplitsPredictor.in")
  val out: Outlet[Seq[(Arrival, Option[Splits])]] = Outlet[Seq[(Arrival, Option[Splits])]]("SplitsPredictor.out")

  val shape: FlowShape[Seq[Arrival], Seq[(Arrival, Option[Splits])]] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      var modelledFlightCodes: Map[TerminalName, Set[String]] = Map()
      var terminalMaybePredictors: Map[TerminalName, Option[SparkSplitsPredictor]] = Map()
      var predictionsToPush: Option[Seq[(Arrival, Option[Splits])]] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          log.info(s"onPush")

          if (predictionsToPush.isEmpty) {
            val arrivalsByTerminal = grab(in).groupBy(_.Terminal)
            log.info(s"grabbed ${arrivalsByTerminal.values.map(_.length).sum} incomingArrivals for predictions")
            predictionsToPush = Option(arrivalPredictions(arrivalsByTerminal))
          } else {
            log.info(s"ignoring onPush() as we've not yet emitted our current arrivals")
          }
          tryPushing()

          if (!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info(s"onPull")
          tryPushing()

          if (!hasBeenPulled(in)) pull(in)
        }
      })

      def tryPushing(): Unit = {
        predictionsToPush match {
          case None => log.info("No arrivals to push")
          case Some(toPush) if isAvailable(out) =>
            log.info(s"Pushing ${toPush.length} arrival predictions")
            push(out, toPush)
            predictionsToPush = None
          case Some(arrivalsToPush) =>
            log.info(s"Can't push ${arrivalsToPush.length} arrivals with prediction. outlet not available")
        }
      }

      def arrivalPredictions(arrivalsByTerminal: Map[String, Seq[Arrival]]): Seq[(Arrival, Option[Splits])] = {
        val predictions: Seq[(Arrival, Option[Splits])] = arrivalsByTerminal
          .toSeq
          .flatMap {
            case (terminalName, terminalArrivals) =>
              val flightCodesToModel = terminalArrivals.map(_.IATA).toSet
              val modelledTerminalFlightCodes = modelledFlightCodes.getOrElse(terminalName, Set())
              val unseenFlightCodes = flightCodesToModel -- modelledTerminalFlightCodes

              if (unseenFlightCodes.nonEmpty) {
                log.info(s"$terminalName: ${unseenFlightCodes.size} unmodelled flight codes. Re-training")
                val updatedFlightCodesToModel = modelledTerminalFlightCodes ++ unseenFlightCodes
                val predictor = splitsPredictorFactory.predictor(updatedFlightCodesToModel)
                terminalMaybePredictors = terminalMaybePredictors.updated(terminalName, Option(predictor))
                modelledFlightCodes = modelledFlightCodes.updated(terminalName, updatedFlightCodesToModel)
              } else {
                log.info(s"$terminalName: No new unmodelled flight codes so no need to re-train")
              }

              terminalMaybePredictors
                .get(terminalName)
                .flatten
                .map(_.predictForArrivals(terminalArrivals))
          }
          .flatten
        predictions
      }
    }
}
