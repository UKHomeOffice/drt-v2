package passengersplits.core

import akka.actor._
import akka.event.LoggingReceive
import passengersplits.core
import core.PassengerInfoRouterActor._
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import spatutorial.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import spatutorial.shared.SDateLike
import services.SDate.implicits._
import spatutorial.shared.PassengerSplits.{FlightNotFound, FlightsNotFound, VoyagePaxSplits}

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

  case class FlightPaxSplitBatchComplete(completionMonitor: ActorRef)

  case object FlightPaxSplitBatchInit

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

  def getRCActor(id: String) = childActorMap getOrElse(id, {
    val c = context actorOf childProps
    childActorMap += id -> c
    context watch c
    log.info(s"created actor ${id}")
    c
  })
}

class PassengerSplitsInfoByPortRouter extends
  Actor with PassengerQueueCalculator with ActorLogging
  with SimpleRouterActor[PassengerInfoRouterActor] {

  def childProps = Props[PassengerInfoRouterActor]

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case FlightPaxSplitBatchInit =>
      sender ! PassengerSplitsAck
    case info: VoyagePassengerInfo =>
      log.info(s"telling children about ${info.summary}")
      val child = getRCActor(childName(info.ArrivalPortCode))
      child.tell(info, sender)
    case report: ReportVoyagePaxSplit =>
      val name = childName(report.destinationPort)
      log.info(s"Looking for ${report} in $name")
      val child = childActorMap get (name)
      child match {
        case Some(c) => c.tell(report, sender)
        case None =>
          log.error("Child singleflight actor doesn't exist yet  ")
          sender ! FlightNotFound(report.carrierCode, report.voyageNumber, report.scheduledArrivalDateTime)
      }
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"top level router asked to ${report}")
      val child = getRCActor(childName(report.destinationPort))
      child.tell(report, sender)
    case FlightPaxSplitBatchComplete(completionMonitor) =>
      log.info(s"FlightPaxSplitBatchComplete received telling $completionMonitor")
      completionMonitor ! FlightPaxSplitBatchComplete(Actor.noSender)
    case report: ReportFlightCode =>
      childActorMap.values.foreach(_.tell(report, sender))
    case LogStatus =>
      childActorMap.values.foreach(_ ! LogStatus)
    case default =>
      log.error(s"$self got an unhandled message ${default}")
  }

  def childName(arrivalPortCode: String): String = {
    s"pax-split-calculator-${arrivalPortCode}"
  }
}

class PassengerInfoRouterActor extends Actor with ActorLogging
  with SimpleRouterActor[SingleFlightActor] {

  def childProps = Props(classOf[SingleFlightActor])

  def receive = LoggingReceive {
    case info: VoyagePassengerInfo =>
      val child = getRCActor(childName(info.ArrivalPortCode, info.CarrierCode, info.VoyageNumber, info.scheduleArrivalDateTime.get))
      child.tell(info, sender)
    case report: ReportVoyagePaxSplit =>
      val name: String = childName(report.destinationPort, report.carrierCode, report.voyageNumber,
        report.scheduledArrivalDateTime)
      log.info(s"Asking $name for paxSplits")
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
    s"flight-pax-calculator-$port-$paddedVoyageNumber-$scheduledArrivalDt"
  }
}

class SingleFlightActor
  extends Actor with PassengerQueueCalculator with ActorLogging {
  var latestMessage: Option[VoyagePassengerInfo] = None

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
    case info: VoyagePassengerInfo =>
      log.info(s"${self} SingleFlightActor received ${info.summary}")
      latestMessage = Option(info)
      log.info(s"$self latestMessage now set ${latestMessage.toString.take(30)}")
      log.info(s"$self Acking to $sender")
      sender ! PassengerSplitsAck


    case ReportVoyagePaxSplit(port, carrierCode, requestedVoyageNumber, scheduledArrivalDateTime) =>
      val replyTo = sender()
      val paddedVoyageNumber = padTo4Digits(requestedVoyageNumber)


      log.info(s"I am ${latestMessage.map(_.summary)}: ReportVoyagePaxSplit for $port $carrierCode $paddedVoyageNumber $scheduledArrivalDateTime")
      //      log.info(s"Current flights ${latestMessage}")

      def matches: (VoyagePassengerInfo) => Boolean = (flight) => doesFlightMatch(carrierCode, paddedVoyageNumber, scheduledArrivalDateTime, flight)

      val matchingFlights: Option[VoyagePassengerInfo] = latestMessage.find {
        (flight) => {
          matches(flight)
        }
      }
      matchingFlights match {
        case Some(flight) =>
          log.info(s"Matching flight is ${flight.summary}")
          log.info(s"matching and replyTo ${replyTo} sender ${sender}")
          calculateAndSendPaxSplits(sender, port, carrierCode, requestedVoyageNumber, scheduledArrivalDateTime, flight)
        case None =>
          replyTo ! FlightNotFound(carrierCode, requestedVoyageNumber, scheduledArrivalDateTime)
      }
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"Looking for flights between $report")
      val matchingFlights: Option[VoyagePassengerInfo] = latestMessage.filter((flight) => {
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
      val matchingFlights: Option[VoyagePassengerInfo] = latestMessage.filter(_.flightCode == flightCode)
      log.info(s"Will reply ${matchingFlights}")
      for (mf <- matchingFlights)
        sender ! List(mf)
    case LogStatus =>
      log.info(s"Current Status ${latestMessage}")
    case default =>
      log.error(s"Got unhandled $default")
  }

  def calculateAndSendPaxSplits(replyTo: ActorRef,
                                port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyagePassengerInfo): Unit = {
    val splits: VoyagePaxSplits = calculateFlightSplits(port, carrierCode, voyageNumber, scheduledArrivalDateTime, flight)
    log.info(s"$self ${flight.summary} calculated splits to $splits")

    replyTo ! splits
    log.info(s"$self sent response $splits")
  }

  def calculateFlightSplits(port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyagePassengerInfo) = {
    log.info(s"$self calculating splits $port $carrierCode $voyageNumber ${scheduledArrivalDateTime.toString}")
    val paxTypeAndQueueCount: PaxTypeAndQueueCounts = PassengerQueueCalculator.
      convertPassengerInfoToPaxQueueCounts(flight.PassengerList)
    VoyagePaxSplits(port,
      carrierCode, voyageNumber, flight.PassengerList.length, scheduledArrivalDateTime,
      paxTypeAndQueueCount)
  }

  def doesFlightMatch(info: VoyagePassengerInfo, existingMessage: VoyagePassengerInfo): Boolean = {
    doesFlightMatch(info.CarrierCode,
      info.VoyageNumber, info.scheduleArrivalDateTime.get, existingMessage)
  }

  def doesFlightMatch(carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: SDateLike, flight: VoyagePassengerInfo): Boolean = {
    val paddedVoyageNumber = padTo4Digits(voyageNumber)
    log.info(s"match? $carrierCode-$paddedVoyageNumber@$scheduledArrivalDateTime vs ${flight.summary}")
    val timeOpt = flight.scheduleArrivalDateTime
    timeOpt match {
      case Some(flightTime) =>
        val paddedFlightVoyageNumber = padTo4Digits(flight.VoyageNumber)
        paddedFlightVoyageNumber == paddedVoyageNumber &&
          //carrierCode == flight.CarrierCode &&
          scheduledArrivalDateTime.millisSinceEpoch == flightTime.millisSinceEpoch
      case None =>
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
    case fnf: FlightNotFound =>
      log.info(s"Got a not found")
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


