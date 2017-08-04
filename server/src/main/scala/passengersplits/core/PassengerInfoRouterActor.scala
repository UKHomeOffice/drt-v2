package passengersplits.core

import akka.actor._
import akka.event.{LoggingAdapter, LoggingReceive}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import passengersplits.core
import core.PassengerInfoRouterActor._
import drt.shared.FlightsApi.TerminalName
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfoJson, VoyageManifest}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.SDateLike
import services.SDate.implicits._
import drt.shared.PassengerSplits.{FlightNotFound, FlightsNotFound, VoyagePaxSplits}
import server.protobuf.messages.VoyageManifest.{AdvancePassengerInfoStateSnapshotMessage, PassengerInfoJsonMessage, VoyageManifestMessage}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object PassengerInfoRouterActor {

  case class ReportVoyagePaxSplit(destinationPort: String,
                                  carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike)

  case class ReportVoyagePaxSplitBetween(scheduledArrivalDateTimeFrom: SDateLike, scheduledArrivalDateTimeTo: SDateLike)

  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])

  case class InternalVoyagesPaxSplits(splits: VoyagesPaxSplits, replyTo: ActorRef)

  case class ReportFlightCode(flightCode: String)


  case object LogStatus

  case class VoyageManifestZipFileComplete(zipfilename: String, completionMonitor: ActorRef)

  case class VoyageManifestZipFileCompleteAck(zipfilename: String)

  case object ManifestZipFileInit

  case object PassengerSplitsAck

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
  }
}

class AdvancePassengerInfoActor extends PersistentActor with PassengerQueueCalculator with ActorLogging {

  case class State(latestFileName: Option[String],
                   flightManifests: Map[String, VoyageManifest])

  var state = State(None, Map.empty)
  val dcPaxIncPercentThreshold = 50

  val snapshotInterval = 20

  implicit def implLog = log

  def manifestKey(vm: VoyageManifest) = voyageKey(vm.ArrivalPortCode, vm.VoyageNumber, vm.scheduleArrivalDateTime.get)

  private def voyageKey(arrivalPortCode: String, voyageNumber: String, scheduledDate: SDateLike) = {
    s"${arrivalPortCode}-${padTo4Digits(voyageNumber)}@${SDate.jodaSDateToIsoString(scheduledDate)}"
  }

  def apiSnapshotMessageFromState: AdvancePassengerInfoStateSnapshotMessage =
    AdvancePassengerInfoStateSnapshotMessage(state.flightManifests.values.map(voyageManifestToMessage).toList)

  override def receiveCommand = LoggingReceive {
    case ManifestZipFileInit =>
      log.info(s"AdvancePassengerInfoActor received FlightPaxSplitBatchInit")
      sender ! PassengerSplitsAck

    case manifest: VoyageManifest =>
      persist(voyageManifestToMessage(manifest)) { manifestMessage =>
        log.info(s"API: saving ${manifest.summary}")
        context.system.eventStream.publish(manifestMessage)
      }
      if (APiManifest.shouldAcceptNewManifest(manifest, state.flightManifests.get(manifestKey(manifest)), dcPaxIncPercentThreshold)) {
        addManifest(manifest)
      }
      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info("saving advance passenger info snapshot")
        saveSnapshot(apiSnapshotMessageFromState)
      }
      sender ! PassengerSplitsAck

    case report: ReportVoyagePaxSplit =>
      val replyTo = sender
      Future {
        val key = voyageKey(report.destinationPort, report.voyageNumber, report.scheduledArrivalDateTime)
        val manifest = state.flightManifests.get(key)
        manifest match {
          case Some(m) =>
            val paxTypeAndQueueCount: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(m)
            replyTo ! VoyagePaxSplits(
              report.destinationPort,
              report.carrierCode, report.voyageNumber, m.PassengerList.length, m.scheduleArrivalDateTime.get,
              paxTypeAndQueueCount)
          case None =>
            replyTo ! FlightNotFound(report.carrierCode, report.voyageNumber, report.scheduledArrivalDateTime)
        }
      }
    case report: ReportVoyagePaxSplitBetween =>
      val filteredManifests = state.flightManifests.values.filter(m => {
        SDate.parseString(m.ScheduledDateOfArrival).millisSinceEpoch >= report.scheduledArrivalDateTimeFrom.millisSinceEpoch &&
          SDate(m.ScheduledDateOfArrival).millisSinceEpoch <= report.scheduledArrivalDateTimeTo.millisSinceEpoch
      })
      sender ! filteredManifests

    case VoyageManifestZipFileComplete(zipFilename, completionMonitor) =>
      log.info(s"FlightPaxSplitBatchComplete received telling $completionMonitor")
      state.copy(latestFileName = Option(zipFilename))
      completionMonitor ! VoyageManifestZipFileCompleteAck(zipFilename)
    case default =>
      log.error(s"$self got an unhandled message ${default}")
  }

  def childName(arrivalPortCode: String): String = {
    s"${arrivalPortCode}"
  }

  def voyageManifestToMessage(manifest: VoyageManifest) = VoyageManifestMessage(
    Option(manifest.EventCode),
    Option(manifest.ArrivalPortCode),
    Option(manifest.DeparturePortCode),
    Option(manifest.VoyageNumber),
    Option(manifest.CarrierCode),
    Option(manifest.ScheduledDateOfArrival),
    Option(manifest.ScheduledTimeOfArrival),
    manifest.PassengerList.map(m =>
      PassengerInfoJsonMessage(
        m.DocumentType,
        Option(m.DocumentIssuingCountryCode),
        Option(m.EEAFlag),
        m.Age,
        m.DisembarkationPortCode,
        Option(m.InTransitFlag),
        m.DisembarkationPortCountryCode,
        m.NationalityCountryCode
      )))

  def addManifest(manifest: VoyageManifest) = {
    val key = manifestKey(manifest)
    log.info(s"Adding voyage manifest with key: $key")
    val newManifests = state.flightManifests.updated(key, manifest)
    state = state.copy(flightManifests = newManifests)
  }

  def voyageManifestMessageToVoyageManifest(voyageManifestMessage: VoyageManifestMessage) = voyageManifestMessage match {

    case VoyageManifestMessage(
    Some(eventCode),
    Some(arrivalPortCode),
    Some(departurePortCode),
    Some(voyageNumber),
    Some(carrierCode),
    Some(scheduledDate),
    Some(scheduledTime),
    passengerList)
    =>
      VoyageManifest(eventCode, arrivalPortCode, departurePortCode, voyageNumber, carrierCode, scheduledDate, scheduledTime, passengerList.collect {
        case PassengerInfoJsonMessage(documentType, Some(countryCode), Some(eeaFlag), age, disembarkationPortCode, Some(inTransitFlag), disembarkationCountryCode, nationalityCode) =>
          PassengerInfoJson(documentType, countryCode, eeaFlag, age, disembarkationPortCode, inTransitFlag, disembarkationCountryCode, nationalityCode)
      }.toList)
  }

  override def receiveRecover: Receive = {
    case vmm: VoyageManifestMessage =>
      val vm = voyageManifestMessageToVoyageManifest(vmm)
      log.info(s"Restoring voyage manifest from message")
      addManifest(vm)
    case SnapshotOffer(_, snapshot: AdvancePassengerInfoStateSnapshotMessage) =>
      log.info(s"Restoring snapshot from protobuf message")
      val flightManifestsFromSnapshot = snapshot.voyageManifestMessages.map(voyageManifestMessageToVoyageManifest)
      state = state.copy(flightManifests = flightManifestsFromSnapshot.map(manifest => (manifestKey(manifest), manifest)).toMap)
    case RecoveryCompleted =>
      log.info(s"Finished recovering ${state.flightManifests.values.size} voyage manifests")
    case other =>
      log.info(s"API: Failed to recover $other")
  }

  override def persistenceId: String = "passenger-manifest-store"
}

object APiManifest {
  def shouldAcceptNewManifest(candidate: VoyageManifest, existingOption: Option[VoyageManifest], dcPaxIncPercentThreshold: Int)(implicit log: LoggingAdapter): Boolean = {
    existingOption match {
      case None => true
      case Some(existing) =>
        isDcManifestSensible(candidate, dcPaxIncPercentThreshold, log, existing)
    }
  }

  private def isDcManifestSensible(candidate: VoyageManifest, dcPaxIncPercentThreshold: Int, log: LoggingAdapter, existing: VoyageManifest) = {
    val existingPax = existing.PassengerList.length
    val candidatePax = candidate.PassengerList.length
    val percentageDiff = (100 * (Math.abs(existingPax - candidatePax).toDouble / existingPax)).toInt
    log.info(s"${existing.flightCode} ${existing.EventCode} had $existingPax pax. ${candidate.EventCode} has $candidatePax pax. $percentageDiff% difference")

    if (existing.EventCode == EventCodes.CheckIn && candidate.EventCode == EventCodes.DoorsClosed && percentageDiff > dcPaxIncPercentThreshold) {
      log.info(s"${existing.flightCode} DC message with $percentageDiff% difference in pax. Not trusting it")
      false
    }
    else true
  }
}


