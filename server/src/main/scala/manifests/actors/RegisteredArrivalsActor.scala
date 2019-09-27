package manifests.actors

import actors.{GetState, PersistentDrtActor, RecoveryActorLike, Sizes}
import drt.shared.{ArrivalKey, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap
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

      val updatesToPersist = findUpdatesToPersist(SortedMap[ArrivalKey, Option[Long]]() ++ newArrivals)
      if (updatesToPersist.nonEmpty) {
        val messageToPersist = arrivalsToMessage(mutable.SortedMap[ArrivalKey, Option[Long]]() ++ updatesToPersist)
        persistAndMaybeSnapshot(messageToPersist)
      }

      state.arrivals ++= newArrivals
      Crunch.purgeExpired(state.arrivals, ArrivalKey.atTime, now, expireAfterMillis.toInt)
  }

  private def findUpdatesToPersist(newArrivals: SortedMap[ArrivalKey, Option[Long]]): SortedMap[ArrivalKey, Option[Long]] = newArrivals.filter {
    case (arrivalToCheck, lastLookup) if hasChanged(arrivalToCheck, lastLookup) => true
  }

  private def hasChanged(arrivalToCheck: ArrivalKey, lastLookup: Option[Long]): Boolean = {
    !state.arrivals.contains(arrivalToCheck) || state.arrivals(arrivalToCheck) != lastLookup
  }

  private def addRegisteredArrivalsFromMessages(arrivalMessages: Seq[RegisteredArrivalMessage]): Unit = {
    val arrivalsFromMessages: Seq[(ArrivalKey, Option[Long])] = arrivalMessages.map {
      case RegisteredArrivalMessage(Some(origin), _, Some(voyageNumber), Some(scheduled), lookedUp) =>
        (ArrivalKey(origin, voyageNumber, scheduled), lookedUp)
    }

    state.arrivals ++= arrivalsFromMessages
  }
}
