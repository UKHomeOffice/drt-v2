package manifests.graph

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import drt.shared.Arrival
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import services.{ManifestLookupLike, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


case class ManifestTries(tries: List[Try[BestAvailableManifest]]) {
  def +(triesToAdd: List[Try[BestAvailableManifest]]) = ManifestTries(tries ++ triesToAdd)
  def nonEmpty: Boolean = tries.nonEmpty
  def length: Int = tries.length
}

object ManifestTries {
  def empty: ManifestTries = ManifestTries(List())
}

class ExecutorStage(portCode: String, manifestLookup: ManifestLookupLike) extends GraphStage[FlowShape[List[ArrivalKey], ManifestTries]] {
  val inArrivals: Inlet[List[ArrivalKey]] = Inlet[List[ArrivalKey]]("inArrivals.in")
  val outManifests: Outlet[ManifestTries] = Outlet[ManifestTries]("outManifests.out")

  override def shape = new FlowShape(inArrivals, outManifests)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var toPush: ManifestTries = ManifestTries(List())

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incomingArrivals = grab(inArrivals)

        val start = SDate.now().millisSinceEpoch
        val manifestTries: List[Try[BestAvailableManifest]] = Try(Await.result(futureManifests(incomingArrivals), 30 seconds)) match {
          case Success(tries) =>
            log.info(s"lookups took ${SDate.now().millisSinceEpoch - start}ms")
            tries
          case Failure(t) =>
            log.error(s"Manifests lookup failed", t)
            List()
        }
        toPush = toPush + manifestTries

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
        log.info(s"Pushing ${toPush.length} manifest tries")
        push(outManifests, toPush)
        toPush = ManifestTries.empty
      }

      if (!hasBeenPulled(inArrivals)) {
        log.info(s"Pulling inArrivals")
        pull(inArrivals)
      }
    }
  }

  private def futureManifests(incomingArrivals: List[ArrivalKey]): Future[List[Try[BestAvailableManifest]]] =
    Future.sequence(
      incomingArrivals
        .map { arrival =>
          manifestLookup
            .tryBestAvailableManifest(portCode, arrival.origin, arrival.voyageNumber, SDate(arrival.scheduled))
        })
}

case class ArrivalKey(origin: String, voyageNumber: String, scheduled: Long)

object ArrivalKey {
  def apply(arrival: Arrival): ArrivalKey = ArrivalKey(arrival.Origin, arrival.voyageNumberPadded, arrival.Scheduled)
}
