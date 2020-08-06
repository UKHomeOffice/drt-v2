package manifests.actors

import actors.{GetState, PersistentDrtActor, RecoveryActorLike, Sizes}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap


case class RegisteredArrivals(arrivals: SortedMap[ArrivalKey, Option[Long]])

class RegisteredArrivalsActor(val initialSnapshotBytesThreshold: Int,
                              val initialMaybeSnapshotInterval: Option[Int],
                              portCode: PortCode,
                              val now: () => SDateLike,
                              expireAfterMillis: Int
                             ) extends RecoveryActorLike with PersistentDrtActor[RegisteredArrivals] {
  override def persistenceId: String = "registered-arrivals"

  override def initialState: RegisteredArrivals = RegisteredArrivals(SortedMap())

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  var state: RegisteredArrivals = RegisteredArrivals(SortedMap())

  override def stateToMessage: GeneratedMessage = arrivalsToMessage(state.arrivals)

  private def arrivalsToMessage(arrivalWithLastLookup: SortedMap[ArrivalKey, Option[Long]]): RegisteredArrivalsMessage = {
    RegisteredArrivalsMessage(
      arrivalWithLastLookup
        .map { case (ArrivalKey(o, v, s), l) =>
          val paddedVoyageNumber = PcpPax.padTo4Digits(v.toString)
          RegisteredArrivalMessage(Option(o.toString), Option(portCode.toString), Option(paddedVoyageNumber), Option(s), l)
        }
        .toSeq
    )
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      addRegisteredArrivalsFromMessages(arrivalMessages)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case RegisteredArrivalsMessage(arrivalMessages) =>
      log.info(s"Got a snapshot containing ${arrivalMessages.length} arrivals")
      state = state.copy(arrivals = SortedMap())
      addRegisteredArrivalsFromMessages(arrivalMessages)
  }

  override def receiveCommand: Receive = {
    case GetState =>
      log.info(s"Received request for current state. Sending ${state.arrivals.size} arrivals")
      sender() ! state

    case RegisteredArrivals(incomingArrivals) =>
      log.debug(s"Received ${incomingArrivals.size} arrivals updates")

      val arrivalsToBeRegistered = findUpdatesToPersist(SortedMap[ArrivalKey, Option[Long]]() ++ incomingArrivals)
      if (arrivalsToBeRegistered.nonEmpty) {
        val messageToPersist = arrivalsToMessage(SortedMap[ArrivalKey, Option[Long]]() ++ arrivalsToBeRegistered)
        persistAndMaybeSnapshot(messageToPersist)
      }

      state = state.copy(arrivals = Crunch.purgeExpired(state.arrivals ++ arrivalsToBeRegistered, ArrivalKey.atTime, now, expireAfterMillis.toInt))
  }

  private def findUpdatesToPersist(newArrivals: SortedMap[ArrivalKey, Option[Long]]): SortedMap[ArrivalKey, Option[Long]] =
    newArrivals.filter {
      case (arrivalToCheck, lastLookup) => lastUpdatedHasChanged(arrivalToCheck, lastLookup)
    }

  private def lastUpdatedHasChanged(arrivalToCheck: ArrivalKey, lastLookup: Option[Long]): Boolean = {
    !state.arrivals.contains(arrivalToCheck) || state.arrivals(arrivalToCheck) != lastLookup
  }

  private def addRegisteredArrivalsFromMessages(arrivalMessages: Seq[RegisteredArrivalMessage]): Unit = {
    val arrivalsFromMessages: Seq[(ArrivalKey, Option[Long])] = arrivalMessages
      .collect {
        case RegisteredArrivalMessage(Some(origin), _, Some(voyageNumberString), Some(scheduled), lookedUp) =>
          VoyageNumber(voyageNumberString) match {
            case vn: VoyageNumber => Option((ArrivalKey(PortCode(origin), vn, scheduled), lookedUp))
            case _ => None
          }
      }
      .collect { case Some(keyAndLookedUp) => keyAndLookedUp }

    state = state.copy(arrivals = state.arrivals ++ arrivalsFromMessages)
  }
}
