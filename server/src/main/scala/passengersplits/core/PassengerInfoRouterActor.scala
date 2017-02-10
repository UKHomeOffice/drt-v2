package passengersplits.core

import akka.actor.Actor.Receive
import akka.actor._
import akka.event.LoggingReceive
import passengersplits.core
import core.PassengerInfoRouterActor._
import core.PassengerQueueTypes.PaxTypeAndQueueCounts
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import spray.http.DateTime

object PassengerInfoRouterActor {

  case class ReportVoyagePaxSplit(destinationPort: String,
                                  carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: DateTime)

  case class ReportVoyagePaxSplitBetween(destinationPort: String,
                                         scheduledArrivalDateTimeFrom: DateTime,
                                         scheduledArrivalDateTimeTo: DateTime)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: DateTime,
                             paxSplits: PaxTypeAndQueueCounts)
  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])
  case class ReportFlightCode(flightCode: String)

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: DateTime)

  case object ProcessedFlightInfo

  case object LogStatus

}

trait SimpleRouterActor[C <: Actor] {
  self: Actor with ActorLogging =>
  var childActorMap = Map.empty[String, ActorRef]

  def childProps: Props

  def getRCActor(id: String) = childActorMap get id getOrElse {
    val c = context actorOf childProps
    childActorMap += id -> c
    context watch c
    log.info(s"created actor ${id}")
    c
  }
}

class PassengerInfoByPortRouter extends
  Actor with PassengerQueueCalculator with ActorLogging
  with SimpleRouterActor[PassengerInfoRouterActor] {

  def childProps = Props[PassengerInfoRouterActor]

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case info: VoyagePassengerInfo =>
      log.info(s"telling children about ${info}")
      val child = getRCActor(childName(info.ArrivalPortCode))
      child.tell(info, sender)
    case report: ReportVoyagePaxSplit =>
      val child = getRCActor(childName(report.destinationPort))
      child.tell(report, sender)
    case report: ReportVoyagePaxSplitBetween =>
      log.info(s"top level router asked to ${report}")
      val child = getRCActor(childName(report.destinationPort))
      child.tell(report, sender)
    case report: ReportFlightCode =>
      childActorMap.values.foreach(_.tell(report, sender))
    case LogStatus =>
      childActorMap.values.foreach(_ ! LogStatus)
    case default =>
      log.error(s"got an unhandled message ${default}")
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

  def childName(port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDt: DateTime) = {
    s"flight-pax-calculator-$port-$carrierCode-$voyageNumber-$scheduledArrivalDt"
  }
}

class SingleFlightActor
  extends Actor with PassengerQueueCalculator with ActorLogging {
  var latestMessage: Option[VoyagePassengerInfo] = None

  def receive = LoggingReceive {
    case info: VoyagePassengerInfo =>
      latestMessage = Option(info)
      log.info(s"${self} received ${info}")
      sender ! ProcessedFlightInfo
    case ReportVoyagePaxSplit(port, carrierCode, voyageNumber, scheduledArrivalDateTime) =>
      log.info(s"Report flight split for $port $carrierCode $voyageNumber $scheduledArrivalDateTime")
//      log.info(s"Current flights ${latestMessage}")
      val matchingFlights: Option[VoyagePassengerInfo] = latestMessage.find {
        (flight) => {
          doesFlightMatch(carrierCode, voyageNumber, scheduledArrivalDateTime, flight)
        }
      }
      log.debug(s"Matching flight is ${matchingFlights}")
      matchingFlights match {
        case Some(flight) =>
          calculateAndSendPaxSplits(sender, port, carrierCode, voyageNumber, scheduledArrivalDateTime, flight)
        case None =>
          sender ! FlightNotFound(carrierCode, voyageNumber, scheduledArrivalDateTime)
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
          calculateAndSendPaxSplits(sender, f.ArrivalPortCode, f.CarrierCode, f.VoyageNumber,
            f.scheduleArrivalDateTime.get, f)
        case None => sender ! FlightNotFound
      }
    case ReportFlightCode(flightCode) =>
      val matchingFlights: Option[VoyagePassengerInfo] = latestMessage.filter(_.flightCode == flightCode)
      log.info(s"Will reply ${matchingFlights}")
      for (mf <- matchingFlights)
        sender ! List(mf)
    case LogStatus =>
      log.info(s"Current Status ${latestMessage}")
    case default =>
      log.info(s"Got unhandled $default")
  }

  def calculateAndSendPaxSplits(replyTo: ActorRef,
                                port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: DateTime, flight: VoyagePassengerInfo): Unit = {
    val splits: VoyagePaxSplits = calculateFlightSplits(port, carrierCode, voyageNumber, scheduledArrivalDateTime, flight)
    replyTo ! splits
    log.info(s"$self sent response")
  }

  def calculateFlightSplits(port: String, carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: DateTime, flight: VoyagePassengerInfo) = {
    log.info(s"$self calculating splits")
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

  def doesFlightMatch(carrierCode: String, voyageNumber: String, scheduledArrivalDateTime: DateTime, flight: VoyagePassengerInfo): Boolean = {
    flight.VoyageNumber == voyageNumber && carrierCode == flight.CarrierCode && Some(scheduledArrivalDateTime) == flight.scheduleArrivalDateTime
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
    case FlightNotFound =>
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


