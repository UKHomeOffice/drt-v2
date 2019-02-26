package manifests.graph

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

class BatchRequestsStage(now: () => SDateLike) extends GraphStage[FlowShape[List[Arrival], List[Arrival]]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[Arrival]] = Outlet[List[Arrival]]("outArrivals.out")

  override def shape = new FlowShape(inArrivals, outArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var subscribers: Map[Arrival, Option[Long]] = Map()
    var prioritised: List[Arrival] = List()

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        incoming.foreach { arrival =>
          subscribers.get(arrival) match {
            case None =>
              addToPrioritised(arrival)
              addToSubscribers(arrival)
            case Some(None) if isWithinHours(arrival.Scheduled, 48) => addToPrioritised(arrival)
            case Some(Some(lastLookupMillis)) if isDueLookup(arrival, lastLookupMillis) => addToPrioritised(arrival)
            case _ => log.info(s"Existing subscriber with a sufficiently recent lookup. Ignoring")
          }
        }

        if (isAvailable(outArrivals)) {
          prioritiseAndPush(subscribers, prioritised)
        }

        pullIfAvailable()
      }
    })

    setHandler(outArrivals, new OutHandler {
      override def onPull(): Unit = {
        prioritiseAndPush(subscribers, prioritised)

        pullIfAvailable()
      }
    })

    private def pullIfAvailable(): Unit = {
      if (!hasBeenPulled(inArrivals)) {
        log.info(s"Pulling inArrivals")
        pull(inArrivals)
      }
    }

    private def prioritiseAndPush(existingSubscribers: Map[Arrival, Option[MillisSinceEpoch]], existingPrioritised: List[Arrival]): Unit = {
      val prioritisedBatch = updatePrioritisedAndSubscribers(existingSubscribers, existingPrioritised)

      if (prioritisedBatch.nonEmpty) {
        log.info(s"Pushing ${prioritisedBatch.length} prioritised arrivals. ${prioritised.length} prioritised remaining.")
        push(outArrivals, prioritisedBatch)
      } else log.info(s"Nothing to push right now")
    }

    private def updatePrioritisedAndSubscribers(existingSubscribers: Map[Arrival, Option[MillisSinceEpoch]], existingPrioritised: List[Arrival]): List[Arrival] = {
      val updatedPrioritised: List[Arrival] = addToPrioritised(existingSubscribers, existingPrioritised)

      val (prioritisedBatch, remainingPrioritised) = updatedPrioritised.splitAt(500)

      prioritised = remainingPrioritised

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      subscribers = prioritised.foldLeft(subscribers) {
        case (subscribersSoFar, priorityArrival) => subscribersSoFar.updated(priorityArrival, Option(lookupTime))
      }

      prioritisedBatch
    }

    private def addToPrioritised(subscribersToCheck: Map[Arrival, Option[MillisSinceEpoch]], existingPrioritised: List[Arrival]): List[Arrival] = {
      subscribersToCheck.foldLeft(existingPrioritised) {
        case (prioritisedSoFar, (subscriber, None)) =>
          if (!prioritisedSoFar.contains(subscriber)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
        case (prioritisedSoFar, (subscriber, Some(lastLookup))) =>
          if (!prioritisedSoFar.contains(subscriber) && isDueLookup(subscriber, lastLookup)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
      }
    }

    private def addToSubscribers(arrival: Arrival): Unit = {
      subscribers = subscribers.updated(arrival, None)
    }

    private def addToPrioritised(arrival: Arrival): Unit = {
      prioritised = arrival :: prioritised
    }
  }

  private def isDueLookup(arrival: Arrival, lastLookupMillis: MillisSinceEpoch): Boolean = {
    val soonWithExpiredLookup = isWithinHours(arrival.Scheduled, 48) && !wasWithinHours(lastLookupMillis, 24)
    val notSoonWithExpiredLookup = isWithinHours(arrival.Scheduled, 48) && !wasWithinHours(lastLookupMillis, 24 * 7)

    soonWithExpiredLookup || notSoonWithExpiredLookup
  }

  private def isWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = millis <= now().addHours(hours).millisSinceEpoch

  private def wasWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = now().addHours(-hours).millisSinceEpoch <= millis
}
