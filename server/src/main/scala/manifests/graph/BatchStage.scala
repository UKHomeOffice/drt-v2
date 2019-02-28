package manifests.graph

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

class BatchStage(now: () => SDateLike) extends GraphStage[FlowShape[List[Arrival], List[ArrivalKey]]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[ArrivalKey]] = Outlet[List[ArrivalKey]]("outArrivals.out")

  override def shape = new FlowShape(inArrivals, outArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var registeredArrivals: Map[ArrivalKey, Option[Long]] = Map()
    var lookupQueue: List[ArrivalKey] = List()

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        incoming.foreach { arrival =>
          val arrivalKey = ArrivalKey(arrival)
          registeredArrivals.get(arrivalKey) match {
            case None =>
              registerArrival(arrivalKey)
              addToLookupQueue(arrivalKey)
            case Some(Some(lastLookupMillis)) if isDueLookup(arrivalKey, lastLookupMillis) => addToLookupQueue(arrivalKey)
            case _ => log.info(s"Existing subscriber with a sufficiently recent lookup. Ignoring")
          }
        }

        if (isAvailable(outArrivals)) {
          prioritiseAndPush(registeredArrivals, lookupQueue)
        }

        pullIfAvailable()
      }
    })

    setHandler(outArrivals, new OutHandler {
      override def onPull(): Unit = {
        prioritiseAndPush(registeredArrivals, lookupQueue)

        pullIfAvailable()
      }
    })

    private def pullIfAvailable(): Unit = {
      if (!hasBeenPulled(inArrivals)) {
        log.info(s"Pulling inArrivals")
        pull(inArrivals)
      }
    }

    private def prioritiseAndPush(existingSubscribers: Map[ArrivalKey, Option[MillisSinceEpoch]], existingPrioritised: List[ArrivalKey]): Unit = {
      val prioritisedBatch = updatePrioritisedAndSubscribers(existingSubscribers, existingPrioritised)

      if (prioritisedBatch.nonEmpty) {
        log.info(s"Pushing ${prioritisedBatch.length} prioritised arrivals. ${lookupQueue.length} prioritised remaining.")
        push(outArrivals, prioritisedBatch)
      } else log.info(s"Nothing to push right now")
    }

    private def updatePrioritisedAndSubscribers(existingSubscribers: Map[ArrivalKey, Option[MillisSinceEpoch]], existingPrioritised: List[ArrivalKey]): List[ArrivalKey] = {
      val updatedPrioritised: List[ArrivalKey] = addToPrioritised(existingSubscribers, existingPrioritised)

      val (prioritisedBatch, remainingPrioritised) = updatedPrioritised.splitAt(500)

      log.info(s"prioritisedBatch: ${prioritisedBatch.length}. remainingPrioritised: ${remainingPrioritised.length}")
      lookupQueue = remainingPrioritised

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      registeredArrivals = prioritisedBatch.foldLeft(registeredArrivals) {
        case (subscribersSoFar, priorityArrival) => subscribersSoFar.updated(priorityArrival, Option(lookupTime))
      }

      prioritisedBatch
    }

    private def addToPrioritised(subscribersToCheck: Map[ArrivalKey, Option[MillisSinceEpoch]], existingPrioritised: List[ArrivalKey]): List[ArrivalKey] = {
      subscribersToCheck.foldLeft(existingPrioritised) {
        case (prioritisedSoFar, (subscriber, None)) =>
          if (!prioritisedSoFar.contains(subscriber)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
        case (prioritisedSoFar, (subscriber, Some(lastLookup))) =>
          if (!prioritisedSoFar.contains(subscriber) && isDueLookup(subscriber, lastLookup)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
      }.sortBy(_.scheduled)
    }

    private def registerArrival(arrival: ArrivalKey): Unit = {
      registeredArrivals = registeredArrivals.updated(arrival, None)
    }

    private def addToLookupQueue(arrivalKey: ArrivalKey): Unit =
      if (!lookupQueue.contains(arrivalKey))
        lookupQueue = arrivalKey :: lookupQueue

  }

  private def isDueLookup(arrival: ArrivalKey, lastLookupMillis: MillisSinceEpoch): Boolean = {
    val soonWithExpiredLookup = isWithinHours(arrival.scheduled, 48) && !wasWithinHours(lastLookupMillis, 24)
    val notSoonWithExpiredLookup = isWithinHours(arrival.scheduled, 48) && !wasWithinHours(lastLookupMillis, 24 * 7)

    soonWithExpiredLookup || notSoonWithExpiredLookup
  }

  private def isWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = millis <= now().addHours(hours).millisSinceEpoch

  private def wasWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = now().addHours(-hours).millisSinceEpoch <= millis
}
