package actors

import akka.actor._
import akka.util.Timeout
import controllers.FlightState
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.duration._
import scala.language.postfixOps
import akka.persistence._
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightsMessage}
import spatutorial.shared.ApiFlight

case object GetFlights

class FlightsActor(crunchActor: ActorRef) extends PersistentActor with ActorLogging  with FlightState {
  implicit val timeout = Timeout(5 seconds)

  override def persistenceId = "flights-store"

  val receiveRecover: Receive = {
    case FlightsMessage(recoveredFlights)  =>
      log.info(s"Recovering ${recoveredFlights.length} new flights")
      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val lastMidnight = LocalDate.now().toString(formatter)
      onFlightUpdates(recoveredFlights.map( f => {
        ApiFlight(
          f.operator.getOrElse(""),
          f.status.getOrElse(""),
          f.estDT.getOrElse(""),
          f.actDT.getOrElse(""),
          f.estChoxDT.getOrElse(""),
          f.actChoxDT.getOrElse(""),
          f.gate.getOrElse(""),
          f.stand.getOrElse(""),
          f.maxPax.getOrElse(0),
          f.actPax.getOrElse(0),
          f.tranPax.getOrElse(0),
          f.runwayID.getOrElse(""),
          f.baggageReclaimId.getOrElse(""),
          f.flightID.getOrElse(0),
          f.airportID.getOrElse(""),
          f.terminal.getOrElse(""),
          f.iCAO.getOrElse(""),
          f.iATA.getOrElse(""),
          f.origin.getOrElse(""),
          f.schDT.getOrElse(""),
          f.pcpTime.getOrElse(0L)
        )
      }).toList, lastMidnight)
    case SnapshotOffer(_, snapshot: Map[Int, ApiFlight]) =>
      log.info(s"Restoring from snapshot")
      flights = snapshot
    case message => log.info(s"unhandled message - $message")
  }

  val receiveCommand: Receive = {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flights.size}")
      sender ! Flights(flights.values.toList)
    case Flights(newFlights) =>
      val flightsMessage = FlightsMessage(newFlights.map(f => {
        FlightMessage(
          Some(f.Operator),
          Some(f.Status),
          Some(f.EstDT),
          Some(f.ActDT),
          Some(f.EstChoxDT),
          Some(f.ActChoxDT),
          Some(f.Gate),
          Some(f.Stand),
          Some(f.MaxPax),
          Some(f.ActPax),
          Some(f.TranPax),
          Some(f.RunwayID),
          Some(f.BaggageReclaimId),
          Some(f.FlightID),
          Some(f.AirportID),
          Some(f.Terminal),
          Some(f.ICAO),
          Some(f.IATA),
          Some(f.Origin),
          Some(f.SchDT),
          Some(f.PcpTime)
        )}))

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
