package actors

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.FlightState
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.persistence._
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import services.SDate
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.shared._
import services.SDate.implicits._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightsMessage}
import drt.shared.ApiFlight
import actors.FlightMessageConversion._

import scala.util.{Success, Try}

case object GetFlights

case object GetFlightsWithSplits

object FlightMessageConversion {
  def apiFlightToFlightMessage(apiFlight: ApiFlight): FlightMessage = {
    FlightMessage(
      operator = Some(apiFlight.Operator),
      gate = Some(apiFlight.Gate),
      stand = Some(apiFlight.Stand),
      status = Some(apiFlight.Status),
      maxPax = Some(apiFlight.MaxPax),
      actPax = Some(apiFlight.ActPax),
      tranPax = Some(apiFlight.TranPax),
      runwayID = Some(apiFlight.RunwayID),
      baggageReclaimId = Some(apiFlight.BaggageReclaimId),
      flightID = Some(apiFlight.FlightID),
      airportID = Some(apiFlight.AirportID),
      terminal = Some(apiFlight.Terminal),
      iCAO = Some(apiFlight.ICAO),
      iATA = Some(apiFlight.IATA),
      origin = Some(apiFlight.Origin),
      pcpTime = Some(apiFlight.PcpTime),

      scheduled = millisFromApiFlightString(apiFlight.SchDT),
      estimated = millisFromApiFlightString(apiFlight.EstDT),
      touchdown = millisFromApiFlightString(apiFlight.ActDT),
      estimatedChox = millisFromApiFlightString(apiFlight.EstChoxDT),
      actualChox = millisFromApiFlightString(apiFlight.ActChoxDT)
    )
  }

  def millisFromApiFlightString(datetime: String): Option[Long] = datetime match {
    case "" => None
    case _ =>
      Try {
        SDate.parseString(datetime)
      } match {
        case Success(MilliDate(millis)) => Some(millis)
        case _ => None
      }
  }

  def flightMessageToApiFlight(flightMessage: FlightMessage): ApiFlight = {
    flightMessage.schDTOLD match {
      case Some(s) =>
        ApiFlight(
          Operator = flightMessage.operator.getOrElse(""),
          Status = flightMessage.status.getOrElse(""),
          EstDT = flightMessage.estDTOLD.getOrElse(""),
          ActDT = flightMessage.actDTOLD.getOrElse(""),
          EstChoxDT = flightMessage.estChoxDTOLD.getOrElse(""),
          ActChoxDT = flightMessage.actChoxDTOLD.getOrElse(""),
          Gate = flightMessage.gate.getOrElse(""),
          Stand = flightMessage.stand.getOrElse(""),
          MaxPax = flightMessage.maxPax.getOrElse(0),
          ActPax = flightMessage.actPax.getOrElse(0),
          TranPax = flightMessage.tranPax.getOrElse(0),
          RunwayID = flightMessage.runwayID.getOrElse(""),
          BaggageReclaimId = flightMessage.baggageReclaimId.getOrElse(""),
          FlightID = flightMessage.flightID.getOrElse(0),
          AirportID = flightMessage.airportID.getOrElse(""),
          Terminal = flightMessage.terminal.getOrElse(""),
          rawICAO = flightMessage.iCAO.getOrElse(""),
          rawIATA = flightMessage.iATA.getOrElse(""),
          Origin = flightMessage.origin.getOrElse(""),
          SchDT = flightMessage.schDTOLD.getOrElse(""),
          PcpTime = flightMessage.pcpTime.getOrElse(0)
        )
      case None =>
        ApiFlight(
          Operator = flightMessage.operator.getOrElse(""),
          Status = flightMessage.status.getOrElse(""),
          EstDT = apiFlightDateTime(flightMessage.estimated),
          ActDT = apiFlightDateTime(flightMessage.touchdown),
          EstChoxDT = apiFlightDateTime(flightMessage.estimatedChox),
          ActChoxDT = apiFlightDateTime(flightMessage.actualChox),
          Gate = flightMessage.gate.getOrElse(""),
          Stand = flightMessage.stand.getOrElse(""),
          MaxPax = flightMessage.maxPax.getOrElse(0),
          ActPax = flightMessage.actPax.getOrElse(0),
          TranPax = flightMessage.tranPax.getOrElse(0),
          RunwayID = flightMessage.runwayID.getOrElse(""),
          BaggageReclaimId = flightMessage.baggageReclaimId.getOrElse(""),
          FlightID = flightMessage.flightID.getOrElse(0),
          AirportID = flightMessage.airportID.getOrElse(""),
          Terminal = flightMessage.terminal.getOrElse(""),
          rawICAO = flightMessage.iCAO.getOrElse(""),
          rawIATA = flightMessage.iATA.getOrElse(""),
          Origin = flightMessage.origin.getOrElse(""),
          SchDT = apiFlightDateTime(flightMessage.scheduled),
          PcpTime = flightMessage.pcpTime.getOrElse(0)
        )
    }
  }

  def apiFlightDateTime(millisOption: Option[Long]): String = millisOption match {
    case Some(millis: Long) => SDate(millis).toApiFlightString
    case _ => ""
  }
}

class FlightsActor(crunchActor: ActorRef, splitsActor: AskableActorRef) extends PersistentActor with ActorLogging with FlightState {
  implicit val timeout = Timeout(5 seconds)

  override def persistenceId = "flights-store"

  override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    super.onRecoveryFailure(cause, event)
    log.error(cause, "recovery failed in flightsActors")
  }

  val receiveRecover: Receive = {
    case FlightsMessage(recoveredFlights) =>
      log.info(s"Recovering ${recoveredFlights.length} new flights")
      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val lastMidnight = LocalDate.now().toString(formatter)
      onFlightUpdates(recoveredFlights.map(flightMessageToApiFlight).toList, lastMidnight)
    case SnapshotOffer(_, snapshot: Map[Int, ApiFlight]) =>
      log.info(s"Restoring from snapshot")
      flights = snapshot
    case message => log.info(s"unhandled message - $message")
  }

  val receiveCommand: Receive = LoggingReceive {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flights.size}")
      sender ! Flights(flights.values.toList)
    case GetFlightsWithSplits =>
      val startTime = org.joda.time.DateTime.now()
      log.info(s"Being asked for flights with splits and I know about ${flights.size}")
      val replyTo = sender()
      val apiFlights = flights.values.toList
      val allSplitRequests: Seq[Future[ApiFlightWithSplits]] = apiFlights map { flight =>
        val scheduledDate = SDate(flight.SchDT)

        val AdvPaxInfo = "advPaxInfo"
        FlightParsing.parseIataToCarrierCodeVoyageNumber(flight.IATA) match {
          case Some((carrierCode, voyageNumber)) =>

            val future = splitsActor ? ReportVoyagePaxSplit(flight.AirportID,
              flight.Operator, voyageNumber, scheduledDate)
            future map {
              case vps: VoyagePaxSplits =>
                log.info(s"didgot splits ${vps} for ${flight}")
                val paxSplits = vps.paxSplits
                ApiFlightWithSplits(flight, ApiSplits(paxSplits.map(s => ApiPaxTypeAndQueueCount(s.passengerType, s.queueType, s.paxCount)), AdvPaxInfo))
              case notFound: FlightNotFound =>
                log.info(s"notgot splits for ${flight}")
                ApiFlightWithSplits(flight, ApiSplits(Nil, AdvPaxInfo)) //Left(FlightNotFound(carrierCode, voyageNumber, scheduledDate)))
            }
          case None =>
            log.info(s"couldnot parse IATA for ${flight}")
            Future.successful(ApiFlightWithSplits(flight, ApiSplits(Nil, AdvPaxInfo))) //Left(FlightNotFound("n/a", flight.ICAO, scheduledDate))))

        }
      }
      val futureOfSeq: Future[Seq[ApiFlightWithSplits]] = Future.sequence(allSplitRequests)
      futureOfSeq.onFailure {
        case t =>
          log.error(t, s"Failed retrieving all splits for ${allSplitRequests.length} flights")
          //todo should we return a failure, or a partial list, here
      }
      futureOfSeq.onSuccess {
        case s =>
          val replyingAt = org.joda.time.DateTime.now()
          val delta = replyingAt.getMillis - startTime.getMillis
          log.info(s"Replying to GetFlightsWithSplits took ${delta}ms")
          replyTo ! FlightsWithSplits(s.toList)
      }
    case Flights(newFlights) =>
      val flightsMessage = FlightsMessage(newFlights.map(apiFlightToFlightMessage))

      log.info(s"Adding ${newFlights.length} new flights")
      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")

      val lastMidnight = LocalDate.now().toString(formatter)
      onFlightUpdates(newFlights, lastMidnight)
      persist(flightsMessage) { (event: FlightsMessage) =>
        log.info(s"Storing ${event.flightMessages.length} flights")
        context.system.eventStream.publish(event)
      }
      crunchActor ! PerformCrunchOnFlights(newFlights)
    case message => log.error("Actor saw unexpected message: " + message.toString)
  }
}
