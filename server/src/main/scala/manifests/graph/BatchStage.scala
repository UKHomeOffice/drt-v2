package manifests.graph

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

class BatchStage(now: () => SDateLike, isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean) extends GraphStage[FlowShape[List[Arrival], List[ArrivalKey]]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[ArrivalKey]] = Outlet[List[ArrivalKey]]("outArrivals.out")

  override def shape = new FlowShape(inArrivals, outArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var registeredArrivals: Map[ArrivalKey, Option[Long]] = Map()
    var lookupQueue: Set[ArrivalKey] = Set()

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        val currentNow = now()

        incoming.foreach { arrival =>
          val arrivalKey = ArrivalKey(arrival)
          registeredArrivals.get(arrivalKey) match {
            case None =>
              registerArrival(arrivalKey)
              addToLookupQueueWithCheck(arrivalKey)
            case Some(Some(lastLookupMillis)) if isDueLookup(arrivalKey, lastLookupMillis, currentNow) => addToLookupQueueWithCheck(arrivalKey)
            case _ => log.debug(s"Existing subscriber with a sufficiently recent lookup. Ignoring: $arrivalKey")
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

    private def prioritiseAndPush(existingSubscribers: Map[ArrivalKey, Option[MillisSinceEpoch]], existingPrioritised: Set[ArrivalKey]): Unit = {
      val prioritisedBatch = updatePrioritisedAndSubscribers(existingSubscribers, existingPrioritised)

      if (prioritisedBatch.nonEmpty) {
        log.info(s"Pushing ${prioritisedBatch.size} prioritised arrivals. ${lookupQueue.size} prioritised remaining.")
        push(outArrivals, prioritisedBatch.toList)
      } else log.info(s"Nothing to push right now")
    }

    private def updatePrioritisedAndSubscribers(existingSubscribers: Map[ArrivalKey, Option[MillisSinceEpoch]], existingPrioritised: Set[ArrivalKey]): Set[ArrivalKey] = {
      log.info(s"about to check all arrivals for those due a lookup")
      val currentNow = now()
      val updatedPrioritised: Set[ArrivalKey] = addToPrioritised(existingSubscribers, existingPrioritised, currentNow)

      val (prioritisedBatch, remainingPrioritised) = updatedPrioritised.toSeq.sortBy(_.scheduled).splitAt(500)

      lookupQueue = Set[ArrivalKey](remainingPrioritised :_*)

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      registeredArrivals = prioritisedBatch.foldLeft(registeredArrivals) {
        case (subscribersSoFar, priorityArrival) => subscribersSoFar.updated(priorityArrival, Option(lookupTime))
      }

      Set[ArrivalKey](prioritisedBatch :_*)
    }

    private def addToPrioritised(subscribersToCheck: Map[ArrivalKey, Option[MillisSinceEpoch]], prioritised: Set[ArrivalKey], currentNow: SDateLike): Set[ArrivalKey] = subscribersToCheck
      .foldLeft(prioritised) {
        case (prioritisedSoFar, (subscriber, None)) =>
          if (!prioritisedSoFar.contains(subscriber)) prioritisedSoFar + subscriber
          else prioritisedSoFar
        case (prioritisedSoFar, (subscriber, Some(lastLookup))) =>
          if (!prioritisedSoFar.contains(subscriber) && isDueLookup(subscriber, lastLookup, currentNow)) prioritisedSoFar + subscriber
          else prioritisedSoFar
      }

    private def registerArrival(arrival: ArrivalKey): Unit = {
      registeredArrivals = registeredArrivals.updated(arrival, None)
    }

    private def addToLookupQueueWithCheck(arrivalKey: ArrivalKey): Unit =
      if (!lookupQueue.contains(arrivalKey))
        lookupQueue = lookupQueue + arrivalKey

  }
}
