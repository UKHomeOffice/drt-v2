package manifests.actors

import actors.{GetState, PersistentDrtActor, RecoveryActorLike, Sizes}
import drt.shared.{ArrivalKey, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import services.graphstages.Crunch

import scala.collection.mutable


case class RegisteredArrivals(arrivals: mutable.SortedMap[ArrivalKey, Option[Long]])

class RegisteredArrivalsActor(val initialSnapshotBytesThreshold: Int,
                              val initialMaybeSnapshotInterval: Option[Int],
                              portCode: String,
                              now: () => SDateLike,
                              expireAfterMillis: Long
                              ) extends RecoveryActorLike with PersistentDrtActor[RegisteredArrivals] {
  override def persistenceId: String = "registered-arrivals"

  override def initialState: RegisteredArrivals = RegisteredArrivals(mutable.SortedMap())

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  override val state = RegisteredArrivals(mutable.SortedMap())

  override def stateToMessage: GeneratedMessage = arrivalsToMessage(state.arrivals)

  private def arrivalsToMessage(arrivalWithLastLookup: mutable.SortedMap[ArrivalKey, Option[Long]]): RegisteredArrivalsMessage = {
    RegisteredArrivalsMessage(
      arrivalWithLastLookup
        .map { case (ArrivalKey(o, v, s), l) => RegisteredArrivalMessage(Option(o), Option(portCode), Option(v), Option(s), l) }
        .toSeq
    )
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      log.info(s"Got a recovery message containing ${arrivalMessages.length} arrivals")
      addRegisteredArrivalsFromMessages(arrivalMessages)
      log.info(s"Now ${state.arrivals.size} registered arrivals")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      log.info(s"Got a snapshot containing ${arrivalMessages.length} arrivals")
      state.arrivals.clear
      addRegisteredArrivalsFromMessages(arrivalMessages)
  }

  override def receiveCommand: Receive = {
    case GetState =>
      log.info(s"Received request for current state. Sending ${state.arrivals.size} arrivals")
      sender() ! state

    case RegisteredArrivals(newArrivals) =>
      log.info(s"Received ${newArrivals.size} arrivals updates")

      val updatesToPersist = findUpdatesToPersist(newArrivals)
      if (updatesToPersist.nonEmpty) {
        val messageToPersist = arrivalsToMessage(updatesToPersist)
        persistAndMaybeSnapshot(messageToPersist)
      }

      state.arrivals ++= newArrivals
      Crunch.purgeExpired(state.arrivals, now, expireAfterMillis.toInt)
  }

  private def findUpdatesToPersist(newArrivals: mutable.SortedMap[ArrivalKey, Option[Long]]): mutable.SortedMap[ArrivalKey, Option[Long]] = {
    val updates = mutable.SortedMap[ArrivalKey, Option[Long]]()

    newArrivals.foreach {
      case (arrivalToCheck, lastLookup) => if (hasChanged(arrivalToCheck, lastLookup)) updates += (arrivalToCheck -> lastLookup)
    }

    updates
  }

  private def hasChanged(arrivalToCheck: ArrivalKey, lastLookup: Option[Long]): Boolean = {
    !state.arrivals.contains(arrivalToCheck) || state.arrivals(arrivalToCheck) != lastLookup
  }

  private def addRegisteredArrivalsFromMessages(arrivalMessages: Seq[RegisteredArrivalMessage]): Unit = {
    val maybeArrivals = arrivalMessages.map(am => {
      for {
        origin <- am.origin
        voyageNumber <- am.voyageNumber
        scheduled <- am.scheduled
        lookedUp <- am.lookedUp
      } yield (ArrivalKey(origin, voyageNumber, scheduled), Option(lookedUp))
    })

    maybeArrivals.foreach {
      case Some(keyAndLookup) => state.arrivals += keyAndLookup
      case None => Unit
    }
  }
}
