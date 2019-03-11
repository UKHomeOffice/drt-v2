package manifests.graph

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.actors.RegisteredArrivals
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch

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
    var registeredArrivals: Map[ArrivalKey, Option[Long]] = Map()
    var registeredArrivalsUpdates: Map[ArrivalKey, Option[Long]] = Map()
    var lookupQueue: Set[ArrivalKey] = Set()
    var lastRefresh: Long = 0L

    override def preStart(): Unit = {
      if (maybeInitialState.isEmpty) log.warn(s"Did not receive any initial registered arrivals")
      maybeInitialState.foreach { state =>
        log.info(s"Received ${state.arrivals.size} initial registered arrivals")
        registeredArrivals = state.arrivals

        lookupQueue = refreshLookupQueue(now())

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
            registeredArrivalsUpdates = registeredArrivalsUpdates.updated(arrivalKey, None)
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
      purgeExpired()
      val lookupBatch = updatePrioritisedAndSubscribers()

      if (lookupBatch.nonEmpty) {
        log.info(s"Pushing ${lookupBatch.size} lookup requests. ${lookupQueue.size} lookup requests remaining.")
        push(outArrivals, lookupBatch.toList)
      } else log.info(s"Nothing to push right now")
    }

    private def pushRegisteredArrivalsUpdates(): Unit = if (registeredArrivalsUpdates.nonEmpty) {
      log.info(s"Pushing ${registeredArrivalsUpdates.size} registered arrivals updates")
      push(outRegisteredArrivals, RegisteredArrivals(registeredArrivalsUpdates))
      registeredArrivalsUpdates = Map()
    }

    private def purgeExpired(): Unit = {
      lookupQueue = Crunch.purgeExpired(lookupQueue, (a: ArrivalKey) => a.scheduled, now, expireAfterMillis)
      registeredArrivals = Crunch.purgeExpiredTuple(registeredArrivals, (a: ArrivalKey) => a.scheduled, now, expireAfterMillis)
    }

    private def updatePrioritisedAndSubscribers(): Set[ArrivalKey] = {
      val (nextLookupBatch, remainingLookups) = if (shouldRefreshLookupQueue) {
        lastRefresh = now().millisSinceEpoch
        refreshLookupQueue(now()).toSeq.sortBy(_.scheduled).splitAt(batchSize)
      } else {
        lookupQueue.toSeq.sortBy(_.scheduled).splitAt(batchSize)
      }

      lookupQueue = Set[ArrivalKey](remainingLookups: _*)

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      nextLookupBatch.foreach { arrivalForLookup =>
        registeredArrivals = registeredArrivals.updated(arrivalForLookup, Option(lookupTime))
        registeredArrivalsUpdates = registeredArrivalsUpdates.updated(arrivalForLookup, Option(lookupTime))
      }

      Set[ArrivalKey](nextLookupBatch: _*)
    }

    private def shouldRefreshLookupQueue: Boolean = {
      val elapsedMillis = now().millisSinceEpoch - lastRefresh
      elapsedMillis >= minimumRefreshIntervalMillis
    }

    private def refreshLookupQueue(currentNow: SDateLike): Set[ArrivalKey] = registeredArrivals
      .foldLeft(lookupQueue) {
        case (prioritisedSoFar, (arrival, None)) =>
          if (!prioritisedSoFar.contains(arrival))
            prioritisedSoFar + arrival
          else
            prioritisedSoFar
        case (prioritisedSoFar, (arrival, Some(lastLookup))) =>
          if (!prioritisedSoFar.contains(arrival) && isDueLookup(arrival, lastLookup, currentNow))
            prioritisedSoFar + arrival
          else
            prioritisedSoFar
      }

    private def registerArrival(arrival: ArrivalKey): Unit = {
      registeredArrivals = registeredArrivals.updated(arrival, None)
    }

    private def addToLookupQueueWithCheck(arrivalKey: ArrivalKey): Unit =
      if (!lookupQueue.contains(arrivalKey))
        lookupQueue = lookupQueue + arrivalKey

  }
}
