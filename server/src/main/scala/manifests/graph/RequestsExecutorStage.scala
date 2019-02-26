package manifests.graph

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import drt.shared.Arrival
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import services.{ManifestLookupLike, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success

case class FutureManifests(eventualManifests: Future[List[BestAvailableManifest]])

class RequestsExecutorStage(portCode: String, manifestLookup: ManifestLookupLike) extends GraphStage[FlowShape[List[Arrival], List[FutureManifests]]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outManifests: Outlet[List[FutureManifests]] = Outlet[List[FutureManifests]]("outManifests.out")

  override def shape = new FlowShape(inArrivals, outManifests)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var toPush: List[FutureManifests] = List()

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incomingArrivals = grab(inArrivals)

        toPush = futureManifests(incomingArrivals) :: toPush

        pushAndPullIfAvailable()
      }
    })

    setHandler(outManifests, new OutHandler {
      override def onPull(): Unit = {
        pushAndPullIfAvailable()
      }
    })

    private def pushAndPullIfAvailable(): Unit = {
      if (toPush.nonEmpty && isAvailable(outManifests)) {
        log.info(s"Pushing ${toPush.length} future manifests")
        push(outManifests, toPush)
        toPush = List()
      }

      if (!hasBeenPulled(inArrivals)) {
        log.info(s"Pulling inArrivals")
        pull(inArrivals)
      }
    }
  }

  private def futureManifests(incomingArrivals: List[Arrival]): FutureManifests = FutureManifests(
    Future.sequence(
      incomingArrivals
        .map { arrival =>
          manifestLookup
            .tryBestAvailableManifest(portCode, arrival.Origin, arrival.voyageNumberPadded, SDate(arrival.Scheduled))
            .collect {
              case Success(bestManifest) => bestManifest
            }
        }))
}
