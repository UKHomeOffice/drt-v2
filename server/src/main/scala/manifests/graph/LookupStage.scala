package manifests.graph

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import drt.shared.ArrivalKey
import manifests.passengers.BestAvailableManifest
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


case class ManifestTries(tries: List[Option[BestAvailableManifest]]) {
  def +(triesToAdd: List[Option[BestAvailableManifest]]) = ManifestTries(tries ++ triesToAdd)

  def nonEmpty: Boolean = tries.nonEmpty

  def length: Int = tries.length
}

object ManifestTries {
  def empty: ManifestTries = ManifestTries(List())
}

class LookupStage(portCode: String, manifestLookup: ManifestLookupLike) extends GraphStage[FlowShape[List[ArrivalKey], ManifestTries]] {
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
        val manifestTries: List[Option[BestAvailableManifest]] = Try(Await.result(futureManifests(incomingArrivals), 60 seconds)) match {
          case Success(arrivalsWithMaybeManifests) =>
            log.info(s"lookups took ${SDate.now().millisSinceEpoch - start}ms")
            arrivalsWithMaybeManifests.map {
              case (_, maybeManifests) => maybeManifests
            }
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

  private def futureManifests(incomingArrivals: List[ArrivalKey]): Future[List[(UniqueArrivalKey, Option[BestAvailableManifest])]] =
    Future.sequence(
      incomingArrivals
        .map { arrival =>
          manifestLookup
            .maybeBestAvailableManifest(portCode, arrival.origin, arrival.voyageNumber, SDate(arrival.scheduled))
        })
}
