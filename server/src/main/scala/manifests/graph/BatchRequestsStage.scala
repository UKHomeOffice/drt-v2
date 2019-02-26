package manifests.graph

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

class BatchRequestsStage(now: () => SDateLike) extends GraphStage[FlowShape[List[Arrival], List[SimpleArrival]]] {
  val inArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("inArrivals.in")
  val outArrivals: Outlet[List[SimpleArrival]] = Outlet[List[SimpleArrival]]("outArrivals.out")

  override def shape = new FlowShape(inArrivals, outArrivals)

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var subscribers: Map[SimpleArrival, Option[Long]] = Map()
    var prioritised: List[SimpleArrival] = List()

    setHandler(inArrivals, new InHandler {
      override def onPush(): Unit = {
        val incoming = grab(inArrivals)

        log.info(s"grabbed ${incoming.length} requests for arrival manifests")

        incoming.foreach { arrival =>
          val simpleArrival = SimpleArrival(arrival)
          subscribers.get(simpleArrival) match {
            case None =>
              addToPrioritised(simpleArrival)
              addToSubscribers(simpleArrival)
            case Some(None) if isWithinHours(arrival.Scheduled, 48) => addToPrioritised(simpleArrival)
            case Some(Some(lastLookupMillis)) if isDueLookup(simpleArrival, lastLookupMillis) => addToPrioritised(simpleArrival)
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

    private def prioritiseAndPush(existingSubscribers: Map[SimpleArrival, Option[MillisSinceEpoch]], existingPrioritised: List[SimpleArrival]): Unit = {
      val prioritisedBatch = updatePrioritisedAndSubscribers(existingSubscribers, existingPrioritised)

      if (prioritisedBatch.nonEmpty) {
        log.info(s"Pushing ${prioritisedBatch.length} prioritised arrivals. ${prioritised.length} prioritised remaining.")
        push(outArrivals, prioritisedBatch)
      } else log.info(s"Nothing to push right now")
    }

    private def updatePrioritisedAndSubscribers(existingSubscribers: Map[SimpleArrival, Option[MillisSinceEpoch]], existingPrioritised: List[SimpleArrival]): List[SimpleArrival] = {
      val updatedPrioritised: List[SimpleArrival] = addToPrioritised(existingSubscribers, existingPrioritised)

      val (prioritisedBatch, remainingPrioritised) = updatedPrioritised.splitAt(500)

      log.info(s"prioritisedBatch: ${prioritisedBatch.length}. remainingPrioritised: ${remainingPrioritised.length}")
      prioritised = remainingPrioritised

      val lookupTime: MillisSinceEpoch = now().millisSinceEpoch

      subscribers = prioritisedBatch.foldLeft(subscribers) {
        case (subscribersSoFar, priorityArrival) => subscribersSoFar.updated(priorityArrival, Option(lookupTime))
      }

      prioritisedBatch
    }

    private def addToPrioritised(subscribersToCheck: Map[SimpleArrival, Option[MillisSinceEpoch]], existingPrioritised: List[SimpleArrival]): List[SimpleArrival] = {
      subscribersToCheck.foldLeft(existingPrioritised) {
        case (prioritisedSoFar, (subscriber, None)) =>
          if (!prioritisedSoFar.contains(subscriber)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
        case (prioritisedSoFar, (subscriber, Some(lastLookup))) =>
          if (!prioritisedSoFar.contains(subscriber) && isDueLookup(subscriber, lastLookup)) subscriber :: prioritisedSoFar
          else prioritisedSoFar
      }
    }

    private def addToSubscribers(arrival: SimpleArrival): Unit = {
      subscribers = subscribers.updated(arrival, None)
    }

    private def addToPrioritised(arrival: SimpleArrival): Unit = {
      prioritised = arrival :: prioritised
    }
  }

  private def isDueLookup(arrival: SimpleArrival, lastLookupMillis: MillisSinceEpoch): Boolean = {
    val soonWithExpiredLookup = isWithinHours(arrival.scheduled, 48) && !wasWithinHours(lastLookupMillis, 24)
    val notSoonWithExpiredLookup = isWithinHours(arrival.scheduled, 48) && !wasWithinHours(lastLookupMillis, 24 * 7)

    soonWithExpiredLookup || notSoonWithExpiredLookup
  }

  private def isWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = millis <= now().addHours(hours).millisSinceEpoch

  private def wasWithinHours(millis: MillisSinceEpoch, hours: Int): Boolean = now().addHours(-hours).millisSinceEpoch <= millis
}
