package manifests.actors

import actors.{GetState, PersistentDrtActor, RecoveryActorLike, Sizes}
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.{ArrivalKey, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import services.graphstages.Crunch


case class RegisteredArrivals(arrivals: Map[ArrivalKey, Option[Long]])

class RegisteredArrivalsActor(val initialSnapshotBytesThreshold: Int,
                              val initialMaybeSnapshotInterval: Option[Int],
                              now: () => SDateLike,
                              portCode: String,
                              expireAfterMillis: Long) extends RecoveryActorLike with PersistentDrtActor[RegisteredArrivals] {
  override def persistenceId: String = "registered-arrivals"

  override def initialState: RegisteredArrivals = RegisteredArrivals(Map())

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  override var state = RegisteredArrivals(Map())

  override def stateToMessage: GeneratedMessage = arrivalsToMessage(state.arrivals)

  private def arrivalsToMessage(arrivalWithLastLookup: Map[ArrivalKey, Option[Long]]): RegisteredArrivalsMessage = {
    RegisteredArrivalsMessage(
      arrivalWithLastLookup
        .map { case (ArrivalKey(o, v, s), l) => RegisteredArrivalMessage(Option(o), Option(portCode), Option(v), Option(s), l) }
        .toSeq
    )
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      log.info(s"Got a recovery message containing ${arrivalMessages.length} arrivals")
      val newArrivals = arrivalMessagesToRegisteredArrivals(arrivalMessages)
      val newStateArrivals = state.arrivals ++ newArrivals
      log.info(s"Added ${newArrivals.size} to ${state.arrivals.size}. Now ${newStateArrivals.size}")
      state = RegisteredArrivals(newStateArrivals)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      log.info(s"Got a snapshot containing ${arrivalMessages.length} arrivals")
      val newArrivals = arrivalMessagesToRegisteredArrivals(arrivalMessages)
      state = RegisteredArrivals(newArrivals)
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

      val updatedArrivals = state.arrivals ++ newArrivals
      val minusExpired = Crunch.purgeExpiredTuple(updatedArrivals, (a: ArrivalKey) => a.scheduled, now, expireAfterMillis)

      state = RegisteredArrivals(minusExpired)
  }

  private def findUpdatesToPersist(newArrivals: Map[ArrivalKey, Option[Long]]): Map[ArrivalKey, Option[Long]] = {
    newArrivals.foldLeft(Map[ArrivalKey, Option[Long]]()) {
      case (toPersistSoFar, (arrivalToCheck, lastLookup)) =>
        if (!hasChanged(arrivalToCheck, lastLookup)) toPersistSoFar
        else toPersistSoFar.updated(arrivalToCheck, lastLookup)
    }
  }

  private def hasChanged(arrivalToCheck: ArrivalKey, lastLookup: Option[Long]): Boolean = {
    !state.arrivals.contains(arrivalToCheck) || state.arrivals(arrivalToCheck) != lastLookup
  }

  private def arrivalMessagesToRegisteredArrivals(arrivalMessages: Seq[RegisteredArrivalMessage]): Map[ArrivalKey, Option[Long]] = {
    val maybeArrivals = arrivalMessages.map(am => {
      for {
        origin <- am.origin
        voyageNumber <- am.voyageNumber
        scheduled <- am.scheduled
        lookedUp <- am.lookedUp
      } yield (ArrivalKey(origin, voyageNumber, scheduled), Option(lookedUp))
    })

    val newArrivals = maybeArrivals.collect { case Some(keyAndLookup) => keyAndLookup }.toMap
    newArrivals
  }
}
