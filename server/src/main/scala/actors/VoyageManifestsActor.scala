package actors

import akka.actor.ActorLogging
import akka.persistence._
import drt.shared.SDateLike
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import server.protobuf.messages.VoyageManifest._
import services.graphstages.Crunch
import services.{GetLatestZipFilename, SDate, UpdateLatestZipFilename, VoyageManifestState}

import scala.collection.immutable.Seq

class VoyageManifestsActor(now: () => SDateLike, expireAfterMillis: Long) extends PersistentActor with ActorLogging {
  var state = VoyageManifestState(
    manifests = Set(),
    latestZipFilename = defaultLatestZipFilename)

  def defaultLatestZipFilename: String = {
    val yesterday = SDate.now().addDays(-1)
    val yymmddYesterday = f"${yesterday.getFullYear() - 2000}%02d${yesterday.getMonth()}%02d${yesterday.getDate()}%02d"
    s"drt_dq_$yymmddYesterday"
  }

  val snapshotInterval = 500

  override def persistenceId: String = "arrival-manifests"

  override def receiveRecover: Receive = {
    case recoveredLZF: String =>
      log.info(s"Recovery received $recoveredLZF")
      state = state.copy(latestZipFilename = recoveredLZF)

    case m@VoyageManifestLatestFileNameMessage(_, Some(latestFilename)) =>
      log.info(s"Recovery received $m")
      state = state.copy(latestZipFilename = latestFilename)

    case VoyageManifestsMessage(_, manifestMessages) =>
      log.info(s"Recovery received ${manifestMessages.length} updated manifests")
      val updatedManifests = manifestMessages
        .map(voyageManifestFromMessage)
        .toSet -- state.manifests

      state = newStateFromManifests(state.manifests, updatedManifests)

    case SnapshotOffer(md, ss) =>
      log.info(s"Recovery received SnapshotOffer($md)")
      ss match {
        case VoyageManifestStateSnapshotMessage(Some(latestFilename), manifests) =>
          log.info(s"Updating state from VoyageManifestStateSnapshotMessage")
          val updatedStateWithManifests = newStateFromManifests(state.manifests, manifests.map(voyageManifestFromMessage).toSet)
          state = updatedStateWithManifests.copy(latestZipFilename = latestFilename)

        case lzf: String =>
          log.info(s"Updating state from latestZipFilename $lzf")
          state = state.copy(latestZipFilename = lzf)

        case u => log.info(s"Received unexpected snapshot data: $u")
      }

    case RecoveryCompleted =>
      log.info(s"Recovery completed")
  }

  def newStateFromManifests(existing: Set[VoyageManifest], updates: Set[VoyageManifest]): VoyageManifestState = {
    val expired: VoyageManifest => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (vm: VoyageManifest) => vm.scheduleArrivalDateTime.getOrElse(SDate.now()).millisSinceEpoch)
    val minusExpired = existing.filterNot(expired)
    log.info(s"Purged ${existing.size - minusExpired.size} VoyageManifests")

    val withUpdates = minusExpired ++ updates
    state.copy(manifests = withUpdates)
  }

  override def receiveCommand: Receive = {
    case UpdateLatestZipFilename(updatedLZF) if updatedLZF != state.latestZipFilename =>
      log.info(s"Received update - latest zip file is: $updatedLZF")
      state = state.copy(latestZipFilename = updatedLZF)

      persist(latestFilenameToMessage(state.latestZipFilename)) { lzf =>
        log.info(s"Persisting VoyageManifests latestZipFilename ${lzf.latestFilename.getOrElse("")}")
        context.system.eventStream.publish(lzf)
        if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
          log.info(s"Saving VoyageManifests snapshot ${state.latestZipFilename}, ${state.manifests.size} manifests")
          saveSnapshot(stateToMessage(state))
        }
      }

    case UpdateLatestZipFilename(updatedLZF) if updatedLZF == state.latestZipFilename =>
      log.info(s"Received update - latest zip file is: $updatedLZF - no change")

    case VoyageManifests(newManifests) =>
      log.info(s"Received ${newManifests.size} manifests")

      val updates = newManifests -- state.manifests

      updates match {
        case updatedManifests if updatedManifests.nonEmpty =>
          persist(voyageManifestsToMessage(updatedManifests)) {
            vmm =>
              log.info(s"Persisting ${vmm.manifestMessages.size} manifest updates")
              context.system.eventStream.publish(vmm)
              if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
                log.info(s"Saving VoyageManifests snapshot ${
                  state.latestZipFilename
                }, ${
                  state.manifests.size
                } manifests")
                saveSnapshot(stateToMessage(state))
              }
          }
        case _ =>
          log.info(s"No changes to persist")
      }

      state = newStateFromManifests(state.manifests, newManifests)

    case GetState =>
      log.info(s"Being asked for state. Sending ${state.manifests.size} manifests and latest filename: ${state.latestZipFilename}")
      sender() ! state

    case GetLatestZipFilename =>
      log.info(s"Received GetLatestZipFilename request. Sending ${state.latestZipFilename}")
      sender() ! state.latestZipFilename

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.info(s"Received unexpected message $other")
  }

  private def voyageManifestsToMessage(updatedManifests: Set[VoyageManifest]) = {
    VoyageManifestsMessage(
      Option(SDate.now().millisSinceEpoch),
      updatedManifests.map(voyageManifestToMessage).toList
    )
  }

  def latestFilenameToMessage(filename: String): VoyageManifestLatestFileNameMessage = {
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now.millisSinceEpoch),
      latestFilename = Option(filename))
  }

  def passengerInfoToMessage(pi: PassengerInfoJson): PassengerInfoJsonMessage = {
    PassengerInfoJsonMessage(
      documentType = pi.DocumentType,
      documentIssuingCountryCode = Option(pi.DocumentIssuingCountryCode),
      eeaFlag = Option(pi.EEAFlag),
      age = pi.Age,
      disembarkationPortCode = pi.DisembarkationPortCode,
      inTransitFlag = Option(pi.InTransitFlag),
      disembarkationPortCountryCode = pi.DisembarkationPortCountryCode,
      nationalityCountryCode = pi.NationalityCountryCode,
      passengerIdentifier = pi.PassengerIdentifier
    )
  }

  def voyageManifestToMessage(vm: VoyageManifest): VoyageManifestMessage = {
    VoyageManifestMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      eventCode = Option(vm.EventCode),
      arrivalPortCode = Option(vm.ArrivalPortCode),
      departurePortCode = Option(vm.DeparturePortCode),
      voyageNumber = Option(vm.VoyageNumber),
      carrierCode = Option(vm.CarrierCode),
      scheduledDateOfArrival = Option(vm.ScheduledDateOfArrival),
      scheduledTimeOfArrival = Option(vm.ScheduledTimeOfArrival),
      passengerList = vm.PassengerList.map(passengerInfoToMessage)
    )
  }

  def stateToMessage(s: VoyageManifestState): VoyageManifestStateSnapshotMessage = {
    VoyageManifestStateSnapshotMessage(Option(s.latestZipFilename), stateVoyageManifestsToMessages(s.manifests))
  }

  def stateVoyageManifestsToMessages(manifests: Set[VoyageManifest]): Seq[VoyageManifestMessage] = {
    manifests.map(voyageManifestToMessage).toList
  }

  def passengerInfoFromMessage(m: PassengerInfoJsonMessage): PassengerInfoJson = {
    PassengerInfoJson(
      DocumentType = m.documentType,
      DocumentIssuingCountryCode = m.documentIssuingCountryCode.getOrElse(""),
      EEAFlag = m.eeaFlag.getOrElse(""),
      Age = m.age,
      DisembarkationPortCode = m.disembarkationPortCode,
      InTransitFlag = m.inTransitFlag.getOrElse(""),
      DisembarkationPortCountryCode = m.disembarkationPortCountryCode,
      NationalityCountryCode = m.nationalityCountryCode,
      PassengerIdentifier = m.passengerIdentifier
    )
  }

  def voyageManifestFromMessage(m: VoyageManifestMessage): VoyageManifest = {
    VoyageManifest(
      EventCode = m.eventCode.getOrElse(""),
      ArrivalPortCode = m.arrivalPortCode.getOrElse(""),
      DeparturePortCode = m.departurePortCode.getOrElse(""),
      VoyageNumber = m.voyageNumber.getOrElse(""),
      CarrierCode = m.carrierCode.getOrElse(""),
      ScheduledDateOfArrival = m.scheduledDateOfArrival.getOrElse(""),
      ScheduledTimeOfArrival = m.scheduledTimeOfArrival.getOrElse(""),
      PassengerList = m.passengerList.toList.map(passengerInfoFromMessage)
    )
  }
}
