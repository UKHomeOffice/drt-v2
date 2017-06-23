package passengersplits.core

import akka.actor._
import akka.event.LoggingReceive
import passengersplits.core
import core.PassengerInfoRouterActor._
import passengersplits.parsing.VoyageManifestParser.{EventCodes, VoyageManifest}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.SDateLike
import services.SDate.implicits._
import drt.shared.PassengerSplits.{FlightNotFound, FlightsNotFound, VoyagePaxSplits}

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

class PassengerSplitsInfoByPortRouter extends
  Actor with PassengerQueueCalculator with ActorLogging
  with SimpleRouterActor[PassengerInfoRouterActor] {

  def childProps = Props[PassengerInfoRouterActor]

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case ManifestZipFileInit =>
      log.info(s"PassengerSplitsInfoByPortRouter received FlightPaxSplitBatchInit")
      sender ! PassengerSplitsAck
    case info: VoyageManifest =>
      log.info(s"telling children about ${info.summary}")
      val child = getRCActor(childName(info.ArrivalPortCode))
      child.tell(info, sender)
    case report: ReportVoyagePaxSplit =>
      val replyTo = sender
      val handyName = s"${report.destinationPort}/${report.voyageNumber}@${report.scheduledArrivalDateTime.toString}"

      val name = childName(report.destinationPort)
      log.info(s"$replyTo asked for us to look for ${report} in $name, $handyName")
      val child = childActorMap get (name)
      child match {
        case Some(c) =>
          log.debug(s"Child singleflight actor found child $name $handyName ")
          c.tell(report, replyTo)
        case None =>
          log.debug(s"Child singleflight actor doesn't exist yet $name $handyName ")
          replyTo ! FlightNotFound(report.carrierCode, report.voyageNumber, report.scheduledArrivalDateTime)
      }
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"top level router asked to ${report}")
      val child = getRCActor(childName(report.destinationPort))
      child.tell(report, sender)
    case VoyageManifestZipFileComplete(zipfilename, completionMonitor) =>
      log.info(s"FlightPaxSplitBatchComplete received telling $completionMonitor")
      completionMonitor ! VoyageManifestZipFileCompleteAck(zipfilename)
    case report: ReportFlightCode =>
      childActorMap.values.foreach(_.tell(report, sender))
    case LogStatus =>
      childActorMap.values.foreach(_ ! LogStatus)
    case default =>
      log.error(s"$self got an unhandled message ${default}")
  }

  def childName(arrivalPortCode: String): String = {
    s"${arrivalPortCode}"
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
  val dcPaxIncreaseThreshold = 50
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

      val manifestToUse = if (latestMessage.isEmpty) Option(newManifest)
      else {
        if (shouldAcceptNewManifest(newManifest, latestMessage.get, dcPaxIncreaseThreshold)) Option(newManifest)
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

  def shouldAcceptNewManifest(candidate: VoyageManifest, existing: VoyageManifest, dcPaxIncreaseThreshold: Int): Boolean = {
    val ciPax = existing.PassengerList.length
    val dcPax = candidate.PassengerList.length
    val pcDiff = (100 * (Math.abs(ciPax - dcPax).toDouble / ciPax)).toInt
    log.info(s"${existing.flightCode} ${existing.EventCode} had $ciPax pax. ${candidate.EventCode} has $dcPax pax. $pcDiff% difference")

    if (existing.EventCode == EventCodes.CheckIn && candidate.EventCode == EventCodes.DoorsClosed && pcDiff > dcPaxIncreaseThreshold) {
      log.info(s"${existing.flightCode} DC message with $pcDiff% difference in pax. Not trusting it")
      false
    } else true
  }

  def calculateAndSendPaxSplits(replyTo: ActorRef,
                                port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyageManifest): Unit = {
    val splits: VoyagePaxSplits = calculateFlightSplits(port, carrierCode, voyageNumber, scheduledArrivalDateTime, flight, flightEgatePercentage = 0)
    log.info(s"$self ${flight.summary} calculated splits to $splits")

    replyTo ! splits
    log.info(s"$self sent response $splits")
  }


  def calculateFlightSplits(port: String, carrierCode: String, voyageNumber: String,
                            scheduledArrivalDateTime: SDateLike,
                            flight: VoyageManifest, flightEgatePercentage: Double = 0.6d): VoyagePaxSplits = {
    log.info(s"$self calculating splits $port $carrierCode $voyageNumber ${scheduledArrivalDateTime.toString}")
    val paxTypeAndQueueCount: PaxTypeAndQueueCounts = PassengerQueueCalculator.
      convertPassengerInfoToPaxQueueCounts(flight.PassengerList, flightEgatePercentage)
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
      } else {
        childActors.foreach(ref => {
          log.info(s"Telling ${ref} to send me a report ${report}")
          ref ! report
        })
        log.info("Sent requests")
      }
    case vpi: VoyagePaxSplits =>
      log.info(s"Got a response! $vpi")
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
    log.info(s"Have ${responseCount}/${childActors.length} responses")
    if (responseCount >= childActors.length) {
      responseRequestedBy ! VoyagesPaxSplits(responses)
      self ! PoisonPill
    }
  }
}


