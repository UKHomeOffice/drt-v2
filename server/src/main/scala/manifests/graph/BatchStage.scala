package manifests.graph

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.actors.RegisteredArrivals
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import services.metrics.{Metrics, StageTimer}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class BatchStage(now: () => SDateLike,
                 isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean,
                 batchSize: Int,
                 expireAfterMillis: MillisSinceEpoch,
                 maybeInitialState: Option[RegisteredArrivals],
                 sleepMillisOnEmptyPush: Long)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext) extends GraphStage[FanOutShape2[List[Arrival], List[ArrivalKey], RegisteredArrivals]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[ArrivalKey]] = Outlet[List[ArrivalKey]]("outArrivals.out")
  val outRegisteredArrivals: Outlet[RegisteredArrivals] = Outlet[RegisteredArrivals]("outRegisteredArrivals.out")
  val stageName = "batch-manifest-requests"

  override def shape = new FanOutShape2(inArrivals, outArrivals, outRegisteredArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val registeredArrivals: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap()
    val registeredArrivalsUpdates: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap()
    val lookupQueue: mutable.SortedSet[ArrivalKey] = mutable.SortedSet()

    override def preStart(): Unit = {
      if (maybeInitialState.isEmpty) log.warn(s"Did not receive any initial registered arrivals")
      maybeInitialState.foreach { state =>
        log.info(s"Received ${state.arrivals.size} initial registered arrivals")
        registeredArrivals ++= state.arrivals

        refreshLookupQueue(now())

        log.info(s"${registeredArrivals.size} registered arrivals. ${lookupQueue.size} arrivals in lookup queue")
      }
    }

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inArrivals)
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        BatchStage.registerNewArrivals(incoming, registeredArrivals, registeredArrivalsUpdates)

        log.info(s"registeredArrivalsUpdates now has ${registeredArrivalsUpdates.size} items")

        if (isAvailable(outArrivals)) prioritiseAndPush()
        if (isAvailable(outRegisteredArrivals)) pushRegisteredArrivalsUpdates()

        pullIfAvailable()
        timer.stopAndReport()
      }
    })

    setHandler(outArrivals, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outArrivals)
        prioritiseAndPush()

        pullIfAvailable()
        timer.stopAndReport()
      }
    })

    setHandler(outRegisteredArrivals, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outRegisteredArrivals)
        pushRegisteredArrivalsUpdates()

        pullIfAvailable()
        timer.stopAndReport()
      }
    })

    private def pullIfAvailable(): Unit = {
      if (!hasBeenPulled(inArrivals)) pull(inArrivals)
    }

    private def prioritiseAndPush(): Unit = {
      Crunch.purgeExpired(lookupQueue, ArrivalKey.atTime, now, expireAfterMillis.toInt)
      Crunch.purgeExpired(registeredArrivals, ArrivalKey.atTime, now, expireAfterMillis.toInt)

      val lookupBatch = updatePrioritisedAndSubscribers()

      if (lookupBatch.nonEmpty) {
        Metrics.counter(s"$stageName", lookupBatch.size)
        push(outArrivals, lookupBatch.toList)
      } else {
        object PushAfterDelay extends Runnable {
          override def run(): Unit = if (isAvailable(outArrivals)) {
            log.info(s"Pushing empty list after delay of ${sleepMillisOnEmptyPush}ms")
            push(outArrivals, List())
          }
        }
        actorSystem.scheduler.scheduleOnce(sleepMillisOnEmptyPush milliseconds, PushAfterDelay)
      }
    }

    private def pushRegisteredArrivalsUpdates(): Unit = if (registeredArrivalsUpdates.nonEmpty) {
      log.info(s"Pushing ${registeredArrivalsUpdates.size} registered arrivals updates")
      push(outRegisteredArrivals, RegisteredArrivals(registeredArrivalsUpdates))
      registeredArrivalsUpdates.clear
    }

    private def updatePrioritisedAndSubscribers(): Set[ArrivalKey] = {
      refreshLookupQueue(now())

      val nextLookupBatch = lookupQueue.take(batchSize)

      lookupQueue --= nextLookupBatch

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      val updatedLookupTimes = nextLookupBatch.map { arrivalForLookup =>
        (arrivalForLookup, Option(lookupTime))
      }

      registeredArrivals ++= updatedLookupTimes
      registeredArrivalsUpdates ++= updatedLookupTimes

      nextLookupBatch.toSet
    }

    private def refreshLookupQueue(currentNow: SDateLike): Unit = registeredArrivals.foreach {
      case (arrival, None) =>
        lookupQueue += arrival
      case (arrival, Some(lastLookup)) =>
        if (!lookupQueue.contains(arrival) && isDueLookup(arrival, lastLookup, currentNow))
          lookupQueue + arrival
    }
  }
}

object BatchStage {
  def registerNewArrivals(incoming: List[Arrival],
                          arrivalsRegistry: mutable.SortedMap[ArrivalKey, Option[MillisSinceEpoch]],
                          updatesRegistry: mutable.SortedMap[ArrivalKey, Option[MillisSinceEpoch]]): Unit = {
    val unregisteredArrivals = BatchStage.arrivalsToRegister(incoming, arrivalsRegistry)
    arrivalsRegistry ++= unregisteredArrivals
    updatesRegistry ++= unregisteredArrivals
  }

  def arrivalsToRegister(arrivalsToConsider: List[Arrival], arrivalsRegistry: mutable.SortedMap[ArrivalKey, Option[MillisSinceEpoch]]): List[(ArrivalKey, Option[Long])] = arrivalsToConsider
    .map(a => (ArrivalKey(a), None))
    .filterNot { case (k, _) => arrivalsRegistry.contains(k) }
}
