package manifests.graph

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.actors.RegisteredArrivals
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch

import scala.collection.mutable

class BatchStage(now: () => SDateLike,
                 isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean,
                 batchSize: Int,
                 expireAfterMillis: MillisSinceEpoch,
                 maybeInitialState: Option[RegisteredArrivals],
                 minimumRefreshIntervalMillis: Long) extends GraphStage[FanOutShape2[List[Arrival], List[ArrivalKey], RegisteredArrivals]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[ArrivalKey]] = Outlet[List[ArrivalKey]]("outArrivals.out")
  val outRegisteredArrivals: Outlet[RegisteredArrivals] = Outlet[RegisteredArrivals]("outRegisteredArrivals.out")

  override def shape = new FanOutShape2(inArrivals, outArrivals, outRegisteredArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val registeredArrivals: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap()
    val registeredArrivalsUpdates: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap()
    val lookupQueue: mutable.SortedSet[ArrivalKey] = mutable.SortedSet()
    var lastRefresh: Long = 0L

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
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        incoming.foreach { arrival =>
          val arrivalKey = ArrivalKey(arrival)
          if (!registeredArrivals.contains(arrivalKey)) {
            registerArrival(arrivalKey)
            registeredArrivalsUpdates += (arrivalKey -> None)
          }
        }

        if (isAvailable(outArrivals)) prioritiseAndPush()
        if (isAvailable(outRegisteredArrivals)) pushRegisteredArrivalsUpdates()

        pullIfAvailable()
      }
    })

    setHandler(outArrivals, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"outArrivals pulled ")
        prioritiseAndPush()

        pullIfAvailable()
      }
    })

    setHandler(outRegisteredArrivals, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"outRegisteredArrivals pulled ")
        pushRegisteredArrivalsUpdates()

        pullIfAvailable()
      }
    })

    private def pullIfAvailable(): Unit = {
      if (!hasBeenPulled(inArrivals)) {
        log.info(s"Pulling inArrivals")
        pull(inArrivals)
      }
    }

    private def prioritiseAndPush(): Unit = {
      Crunch.purgeExpired(lookupQueue, ArrivalKey.atTime, now, expireAfterMillis.toInt)
      Crunch.purgeExpired(registeredArrivals, ArrivalKey.atTime, now, expireAfterMillis.toInt)

      val lookupBatch = updatePrioritisedAndSubscribers()

      if (lookupBatch.nonEmpty) {
        log.info(s"Pushing ${lookupBatch.size} lookup requests. ${lookupQueue.size} lookup requests remaining.")
        push(outArrivals, lookupBatch.toList)
      } else log.info(s"Nothing to push right now")
    }

    private def pushRegisteredArrivalsUpdates(): Unit = if (registeredArrivalsUpdates.nonEmpty) {
      log.info(s"Pushing ${registeredArrivalsUpdates.size} registered arrivals updates")
      push(outRegisteredArrivals, RegisteredArrivals(registeredArrivalsUpdates))
      registeredArrivalsUpdates.clear
    }

    private def updatePrioritisedAndSubscribers(): Set[ArrivalKey] = {
      val nextLookupBatch = (shouldRefreshLookupQueue, registeredArrivals.nonEmpty) match {
        case (true, true) =>
          log.info(s"Refreshing lookup queue")
          lastRefresh = now().millisSinceEpoch
          refreshLookupQueue(now())
          lookupQueue.take(batchSize)
        case (true, false) =>
          log.info(s"No registered arrivals")
          lookupQueue.take(batchSize)
        case (false, _) =>
          val minRefreshSeconds = minimumRefreshIntervalMillis / 1000
          val secondsSinceLastRefresh = (now().millisSinceEpoch - lastRefresh) / 1000
          log.info(f"Minimum refresh interval: ${minRefreshSeconds}s. $secondsSinceLastRefresh%ds since last refresh. Not refreshing")
          lookupQueue.take(batchSize)
      }

      lookupQueue --= nextLookupBatch

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      nextLookupBatch.foreach { arrivalForLookup =>
        registeredArrivals += (arrivalForLookup -> Option(lookupTime))
        registeredArrivalsUpdates += (arrivalForLookup -> Option(lookupTime))
      }

      nextLookupBatch.toSet
    }

    private def shouldRefreshLookupQueue: Boolean = {
      val elapsedMillis = now().millisSinceEpoch - lastRefresh
      elapsedMillis >= minimumRefreshIntervalMillis
    }

    private def refreshLookupQueue(currentNow: SDateLike): Unit = registeredArrivals.foreach {
      case (arrival, None) =>
        lookupQueue += arrival
      case (arrival, Some(lastLookup)) =>
        if (!lookupQueue.contains(arrival) && isDueLookup(arrival, lastLookup, currentNow))
          lookupQueue + arrival
    }

    private def registerArrival(arrival: ArrivalKey): Unit = {
      registeredArrivals += (arrival -> None)
    }
  }
}
