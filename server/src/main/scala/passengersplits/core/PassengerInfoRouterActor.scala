package passengersplits.core

import akka.actor._
import akka.event.{LoggingAdapter, LoggingReceive}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import passengersplits.core
import core.PassengerInfoRouterActor._
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfoJson, VoyageManifest}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.SDateLike
import services.SDate.implicits._
import drt.shared.PassengerSplits.{FlightNotFound, FlightsNotFound, VoyagePaxSplits}
import server.protobuf.messages.VoyageManifest.{PassengerInfoJsonMessage, VoyageManifestMessage}
import services.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object PassengerInfoRouterActor {

  case class ReportVoyagePaxSplit(destinationPort: String,
                                  carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike)

  case class ReportVoyagePaxSplitBetween(destinationPort: String,
                                         scheduledArrivalDateTimeFrom: SDateLike,
                                         scheduledArrivalDateTimeTo: SDateLike)

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

trait SimpleRouterActor[C <: Actor] {
  self: Actor with ActorLogging =>
  var childActorMap = Map.empty[String, ActorRef]

  def childProps: Props

  def getRCActor(id: String) = {
    childActorMap getOrElse(id, {
      val c = context actorOf childProps
      childActorMap += id -> c
      context watch c
      log.info(s"created actor ${id}")
      c
    })
  }
}

class PassengerSplitsInfoByPortRouter extends PersistentActor with PassengerQueueCalculator with ActorLogging {

  case class State(latestFileName: Option[String],
                   flightManifests: Map[String, VoyageManifest])

  var state = State(None, Map.empty)
  val dcPaxIncPercentThreshold = 50

  implicit def implLog = log

  def manifestKey(vm: VoyageManifest) = s"${vm.ArrivalPortCode}-${padTo4Digits(vm.VoyageNumber)}@${SDate.jodaSDateToIsoString(vm.scheduleArrivalDateTime.get)}"

  override def receiveCommand = LoggingReceive {
    case ManifestZipFileInit =>
      log.info(s"PassengerSplitsInfoByPortRouter received FlightPaxSplitBatchInit")
      sender ! PassengerSplitsAck

    case manifest: VoyageManifest =>
      log.info(s"Got a manifest $manifest")
      persist(voyageManifestToMessage(manifest)) { manifestMessage =>
        log.info(s"API: saving ${manifest.summary}")
        context.system.eventStream.publish(manifestMessage)
      }
      log.info(s"past saving it")
      if (SingleFlightActor.shouldAcceptNewManifest(manifest, state.flightManifests.get(manifestKey(manifest)), dcPaxIncPercentThreshold)) {
        log.info(s"Adding the manifest")
        addManifest(manifest)
      } else {
        log.info(s"not ok manifest")
      }
      sender ! PassengerSplitsAck

    case report: ReportVoyagePaxSplit =>
      val replyTo = sender
      Future {
        val key = s"${report.destinationPort}-${padTo4Digits(report.voyageNumber)}@${SDate.jodaSDateToIsoString(report.scheduledArrivalDateTime)}"
        log.info(s"retrieving $key")
        log.info(s"Current state: $state")
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
      log.info(s"API: Got these splits: ${state.flightManifests}")
      sender ! state.flightManifests.values.filter(m => {
        SDate.parseString(m.ScheduledDateOfArrival).millisSinceEpoch >= report.scheduledArrivalDateTimeFrom.millisSinceEpoch &&
          SDate(m.ScheduledDateOfArrival).millisSinceEpoch <= report.scheduledArrivalDateTimeTo.millisSinceEpoch
      })

    case VoyageManifestZipFileComplete(zipFilename, completionMonitor) =>
      log.info(s"FlightPaxSplitBatchComplete received telling $completionMonitor")
      state.copy(latestFileName = Option(zipFilename))
      completionMonitor ! VoyageManifestZipFileCompleteAck(zipFilename)
    case report: ReportFlightCode =>
      log.info(s"API: Looking for split for ${report.flightCode}")
      sender ! state.flightManifests.values.filter(_.flightCode == report.flightCode)
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
    log.info(s"saving $key")
    val newManifests = state.flightManifests.updated(key, manifest)
    state = state.copy(flightManifests = newManifests)
  }

  override def receiveRecover: Receive = {
    case VoyageManifestMessage(
    Some(eventCode),
    Some(arrivalPortCode),
    Some(departurePortCode),
    Some(voyageNumber),
    Some(carrierCode),
    Some(scheduledDate),
    Some(scheduledTime),
    passengerList) =>
      val vm = VoyageManifest(eventCode, arrivalPortCode, departurePortCode, voyageNumber, carrierCode, scheduledDate, scheduledTime, passengerList.collect {
        case PassengerInfoJsonMessage(documentType, Some(countryCode), Some(eeaFlag), age, disembarkationPortCode, Some(inTransitFlag), disembarkationCountryCode, nationalityCode) =>
          PassengerInfoJson(documentType, countryCode, eeaFlag, age, disembarkationPortCode, inTransitFlag, disembarkationCountryCode, nationalityCode)
      }.toList)
      log.info(s"API: recovering ${vm.summary}")
      addManifest(vm)
    case RecoveryCompleted =>
      log.info(s"Finished recovering flights ${state.flightManifests.values.map(_.flightCode)}")
    case other =>
      log.info(s"API: Failed to recover $other")
  }

  override def persistenceId: String = "passenger-manifest-store"
}


/**
  * attempt to use a single map to see what it performs like - rather than our current implicit map of trees of actors.
  */
class FlatPassengerSplitsInfoByPortRouter extends
  Actor with PassengerQueueCalculator with ActorLogging {

  case class State(latestFileName: Option[String],
                   flightManifests: Map[String, VoyageManifest])

  var state = State(None, Map.empty)

  def manifestKey(vm: VoyageManifest) = s"${vm.ArrivalPortCode}-${vm.CarrierCode}${padTo4Digits(vm.VoyageNumber)}@${SDate.jodaSDateToIsoString(vm.scheduleArrivalDateTime.get)}}"

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case ManifestZipFileInit =>
      log.info(s"PassengerSplitsInfoByPortRouter received FlightPaxSplitBatchInit")
      sender ! PassengerSplitsAck
    case info: VoyageManifest =>
      log.info(s"saving ${info.summary}")
      val key = manifestKey(info)
      log.info(s"saving $key")
      val newManifests = state.flightManifests.updated(key, info)
      state = state.copy(flightManifests = newManifests)
      sender ! PassengerSplitsAck

    case report: ReportVoyagePaxSplit =>
      val replyTo = sender
      Future {
        val key = s"${report.destinationPort}-${report.carrierCode}${padTo4Digits(report.voyageNumber)}@${SDate.jodaSDateToIsoString(report.scheduledArrivalDateTime)}"
        log.info(s"retrieving $key")
        val manifest = state.flightManifests.get(key)
        manifest match {
          case Some(m) =>
            val paxTypeAndQueueCount: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(m)
            VoyagePaxSplits(
              report.destinationPort,
              report.carrierCode, report.voyageNumber, m.PassengerList.length, m.scheduleArrivalDateTime.get,
              paxTypeAndQueueCount)
          case None =>
            replyTo ! FlightNotFound(report.carrierCode, report.voyageNumber, report.scheduledArrivalDateTime)
        }
      }
    case default =>
      log.error(s"$self got an unhandled message ${default}")
  }

}

class PassengerInfoRouterActor extends Actor with ActorLogging
  with SimpleRouterActor[SingleFlightActor] {

  def childProps = Props(classOf[SingleFlightActor])

  def receive = LoggingReceive {
    case info: VoyageManifest =>
      val child = getRCActor(childName(info.ArrivalPortCode, info.CarrierCode, info.VoyageNumber, info.scheduleArrivalDateTime.get))
      child.tell(info, sender)
    case report: ReportVoyagePaxSplit =>
      val name: String = childName(report.destinationPort, report.carrierCode, report.voyageNumber,
        report.scheduledArrivalDateTime)
      log.info(s"$sender is Asking $name for paxSplits with $report")
      val child = getRCActor(name)
      child.tell(report, sender)
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"Router will try and report on ${report}")
      val responseRequestBy: ActorRef = sender()
      val responseActor = context.actorOf(ResponseCollationActor.props(childActorMap.values.toList,
        report, responseRequestBy))
      responseActor ! "begin"
    case report: ReportFlightCode =>
      (childActorMap.values).foreach { case child => child.tell(report, sender) }
    case LogStatus =>
      childActorMap.values.foreach(_ ! LogStatus)
  }

  def childName(port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDt: SDateLike) = {
    val paddedVoyageNumber = padTo4Digits(voyageNumber)
    s"fpc-$port-$paddedVoyageNumber-$scheduledArrivalDt"
  }
}

class SingleFlightActor
  extends Actor with PassengerQueueCalculator with ActorLogging {

  import SingleFlightActor._

   implicit def implLog = log

  val dcPaxIncPercentThreshold = 50
  var latestMessage: Option[VoyageManifest] = None

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    log.info(s"SingleFlightActor starting $self ")
    super.preStart()
  }

  @scala.throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    log.info(s"SingleFlightActor restarting ${self} ")
    super.postRestart(reason)
  }

  def receive = LoggingReceive {
    case newManifest: VoyageManifest =>
      log.info(s"${self} SingleFlightActor received ${newManifest.summary}")

      val manifestToUse = if (latestMessage.isEmpty)
        Option(newManifest)
      else {
        if (shouldAcceptNewManifest(newManifest, latestMessage, dcPaxIncPercentThreshold)) Option(newManifest)
        else latestMessage
      }

      latestMessage = manifestToUse
      log.debug(s"$self latestMessage now set ${latestMessage.toString.take(30)}")
      log.debug(s"$self Acking to $sender")
      sender ! PassengerSplitsAck

    case ReportVoyagePaxSplit(port, carrierCode, requestedVoyageNumber, scheduledArrivalDateTime) =>
      val replyTo = sender()
      val paddedVoyageNumber = padTo4Digits(requestedVoyageNumber)

      val reportName = s"ReportVoyagePaxSplit($port, $carrierCode, $paddedVoyageNumber, $scheduledArrivalDateTime)"
      log.debug(s"$replyTo is asking for I am ${latestMessage.map(_.summary)}: $reportName")

      def matches: (VoyageManifest) => Boolean = (flight) => doesFlightMatch(carrierCode, paddedVoyageNumber, scheduledArrivalDateTime, flight)

      val matchingFlight: Option[VoyageManifest] = latestMessage.find(matches)

      matchingFlight match {
        case Some(flight) =>
          log.debug(s"$replyTo Matching flight is ${flight.summary}")
          calculateAndSendPaxSplits(sender, port, carrierCode, requestedVoyageNumber, scheduledArrivalDateTime, flight)
        case None =>
          log.debug(s"$replyTo did not match ${reportName}")
          replyTo ! FlightNotFound(carrierCode, requestedVoyageNumber, scheduledArrivalDateTime)
      }
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"Looking for flights between $report")
      val matchingFlights: Option[VoyageManifest] = latestMessage.filter((flight) => {
        flight.scheduleArrivalDateTime.exists {
          (dateTime) =>
            dateTime >= report.scheduledArrivalDateTimeFrom && dateTime <= report.scheduledArrivalDateTimeTo
        }
      })
      matchingFlights match {
        case Some(f) =>
          calculateAndSendPaxSplits(sender, f.ArrivalPortCode, f.CarrierCode, padTo4Digits(f.VoyageNumber),
            f.scheduleArrivalDateTime.get, f)
        case None => sender ! FlightsNotFound
      }
    case ReportFlightCode(flightCode) =>
      val matchingFlights: Option[VoyageManifest] = latestMessage.filter(_.flightCode == flightCode)
      log.info(s"Will reply ${matchingFlights}")
      for (mf <- matchingFlights)
        sender ! List(mf)
    case LogStatus =>
      log.info(s"Current Status ${latestMessage}")
    case default =>
      log.error(s"Got unhandled $default")
  }


  def calculateAndSendPaxSplits(replyTo: ActorRef,
                                port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyageManifest): Unit = {
    val splits: VoyagePaxSplits = calculateFlightSplits(port, carrierCode, voyageNumber, scheduledArrivalDateTime, flight, flightEgatePercentage = 0)

    replyTo ! splits
    log.debug(s"$self ${flight.summary} calculated and sent splits: $splits")
  }


  def calculateFlightSplits(port: String, carrierCode: String, voyageNumber: String,
                            scheduledArrivalDateTime: SDateLike,
                            flight: VoyageManifest, flightEgatePercentage: Double = 0.6d): VoyagePaxSplits = {
    log.info(s"$self calculating splits $port $carrierCode $voyageNumber ${scheduledArrivalDateTime.toString}")
    val paxTypeAndQueueCount: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(flight)
    VoyagePaxSplits(
      port,
      carrierCode, voyageNumber, flight.PassengerList.length, scheduledArrivalDateTime,
      paxTypeAndQueueCount)
  }

  def doesFlightMatch(carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyageManifest): Boolean = {
    log.debug(s"doesflightmatch ${carrierCode}${voyageNumber} ${flight.summary} ${scheduledArrivalDateTime} $scheduledArrivalDateTime")

    val paddedVoyageNumber = padTo4Digits(voyageNumber)
    val timeOpt = flight.scheduleArrivalDateTime
    timeOpt match {
      case Some(flightTime) =>
        log.debug(s"doesflightmatch ${carrierCode}${voyageNumber} ${flight.summary} ${scheduledArrivalDateTime} ${scheduledArrivalDateTime.millisSinceEpoch} == ${flightTime.millisSinceEpoch}")
        val paddedFlightVoyageNumber = padTo4Digits(flight.VoyageNumber)
        paddedFlightVoyageNumber == paddedVoyageNumber &&
          scheduledArrivalDateTime.millisSinceEpoch == flightTime.millisSinceEpoch
      case None =>
        log.debug(s"doesflightmatch ${carrierCode}${voyageNumber} ${flight.summary} false")
        false
    }
  }
}

object ResponseCollationActor {
  def props(childActors: List[ActorRef], report: ReportVoyagePaxSplitBetween,
            responseRequestedBy: ActorRef) = Props(classOf[ResponseCollationActor],
    childActors, report, responseRequestedBy)
}

class ResponseCollationActor(childActors: List[ActorRef], report: ReportVoyagePaxSplitBetween,
                             responseRequestedBy: ActorRef
                            ) extends Actor with ActorLogging {
  var responses: List[VoyagePaxSplits] = List.empty[VoyagePaxSplits]
  var responseCount = 0

  def receive = LoggingReceive {
    case "begin" =>
      log.info(s"Sending requests to the children ${childActors.length}")
      if (childActors.isEmpty) {
        log.info(s"No children to get responses for")
        checkIfDoneAndDie()
      }
      else {
        childActors.foreach(ref => {
          log.info(s"Telling ${ref} to send me a report ${report}")
          ref ! report
        }
        )
        log.info("Sent requests")
      }

    case vpi: VoyagePaxSplits =>
      log.debug(s"Got a response! $vpi")
      responseCount += 1
      responses = vpi :: responses
      checkIfDoneAndDie()
    case _: FlightNotFound =>
      log.debug(s"Got a not found")
      responseCount += 1
      checkIfDoneAndDie()
    case default =>
      log.error(s"$self unhandled message $default")
  }

  def checkIfDoneAndDie() = {
    log.debug(s"Have $responseCount/${childActors.length} responses")
    if (responseCount >= childActors.length) {
      responseRequestedBy ! VoyagesPaxSplits(responses)
      self ! PoisonPill
    }
  }
}

object SingleFlightActor {
  def shouldAcceptNewManifest(candidate: VoyageManifest, existingOption: Option[VoyageManifest], dcPaxIncPercentThreshold: Int)(implicit log: LoggingAdapter): Boolean = {
    existingOption match {
      case None => true
      case Some(existing) =>
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
}


