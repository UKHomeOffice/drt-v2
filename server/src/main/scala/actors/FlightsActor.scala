package actors

import actors.FlightMessageConversion._
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.AskableActorRef
import akka.persistence._
import akka.util.Timeout
import controllers.FlightState
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.shared.{Arrival, _}
import org.joda.time.{DateTimeZone, LocalDate}
import org.joda.time.format.DateTimeFormat
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import server.protobuf.messages.FlightsMessage.{FlightLastKnownPaxMessage, FlightStateSnapshotMessage, FlightsMessage}
import services.SplitsProvider.SplitProvider
import services.{CSVPassengerSplitsProvider, FastTrackPercentages, SDate}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

case object GetFlights

case object GetFlightsWithSplits

class FlightsActor(crunchActorRef: ActorRef,
                   dqApiSplitsActorRef: AskableActorRef,
                   csvSplitsProvider: SplitProvider,
                   _bestPax: (Arrival) => Int,
                   pcpArrivalTimeForFlight: (Arrival) => MilliDate)
  extends PersistentActor
    with ActorLogging
    with FlightState
    with DomesticPortList {
  implicit val timeout = Timeout(5 seconds)

  override def bestPax(a: Arrival): Int = _bestPax(a)

  import SplitRatiosNs.SplitSources._

  val snapshotInterval = 20

  override def persistenceId = "flights-store"

  override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    Recovery
    super.onRecoveryFailure(cause, event)
    log.error(cause, "recovery failed in flightsActors")
  }

  val receiveRecover: Receive = {
    case FlightsMessage(recoveredFlights) =>
      log.info(s"Recovering ${recoveredFlights.length} new flights")
      setFlights(recoveredFlights.map(flightMessageToApiFlight).map(f => (f.FlightID, f)).toMap)
    case SnapshotOffer(_, snapshot) =>
      val flightsFromSnapshot = snapshot match {
        case flightStateSnapshot: FlightStateSnapshotMessage =>
          log.info(s"Restoring snapshot from protobuf message")
          flightStateSnapshot.flightMessages.map(flightMessageToApiFlight).map(f => (f.FlightID, f)).toMap
        case flights: Map[Int, Any] =>
          log.info(s"Restoring snapshot from legacy ApiFlight")
          flights.mapValues {
            case a: Arrival => a
            case f: ApiFlight => ApiFlightToArrival(f)
          }
      }
      val lastKnownPaxFromSnapshot = snapshot match {
        case flightStateSnapshot: FlightStateSnapshotMessage =>
          flightStateSnapshot.lastKnownPax.collect {
            case FlightLastKnownPaxMessage(Some(key), Some(pax)) =>
              (key, pax)
          }.toMap
        case _ => Map[String, Int]()
      }
      setFlights(flightsFromSnapshot)
      setLastKnownPax(lastKnownPaxFromSnapshot)

    case RecoveryCompleted =>
      requestCrunch(flightState.values.toList)
      log.info("Flights recovery completed, triggering crunch")
    case message => log.error(s"unhandled message - $message")
  }

  val receiveCommand: Receive = LoggingReceive {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flightState.size}")
      sender ! Flights(flightState.values.toList)

    case GetFlightsWithSplits =>
      log.info(s"Being asked for flights with splits and I know about ${flightState.size}")

      val startTime = org.joda.time.DateTime.now()
      val replyTo = sender()
      val apiFlights = flightState.values.toList
      val allSplitRequests: Seq[Future[ApiFlightWithSplits]] = apiFlights.map(addSplitsToArrival)

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
      val flightsWithPcpTime = newFlights.map(arrival => {
        arrival.copy(PcpTime = pcpArrivalTimeForFlight(arrival).millisSinceEpoch)
      })

      val flightsWithLastKnownPax = addLastKnownPaxNos(flightsWithPcpTime)
      storeLastKnownPaxForFlights(flightsWithLastKnownPax)

      log.info(s"Adding ${flightsWithLastKnownPax.length} new flights")
      val lastMidnight = LocalDate.now().toString(DateTimeFormat.forPattern("yyyy-MM-dd"))

      onFlightUpdates(flightsWithLastKnownPax, lastMidnight, domesticPorts)
      log.debug(s"flight state now contains ${flightState.values.toList}")
      val flightsMessage = FlightsMessage(flightState.values.toList.map(apiFlightToFlightMessage))

      persist(flightsMessage) { (event: FlightsMessage) =>
        log.info(s"Storing ${event.flightMessages.length} flights")
        context.system.eventStream.publish(event)
        if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
          log.info("saving flights snapshot")
          saveSnapshot(flightStateSnapshotMessageFromState)
        }
      }
      requestCrunch(flightsWithLastKnownPax)

    case SaveSnapshotSuccess(metadata) => log.info(s"Finished saving flights snapshot")

    case message => log.error("Actor saw unexpected message: " + message.toString)

  }

  def addSplitsToArrival(flight: Arrival): Future[ApiFlightWithSplits] = {
    FlightParsing.parseIataToCarrierCodeVoyageNumber(flight.IATA) match {
      case Some((_, voyageNumber)) =>
        val scheduledDate = SDate(flight.SchDT, DateTimeZone.UTC)
        val splitsRequest = ReportVoyagePaxSplit(flight.AirportID, flight.Operator, voyageNumber, scheduledDate)
        val future = dqApiSplitsActorRef ? splitsRequest
        val futureResp = future map {
          case vps: VoyagePaxSplits =>
            splitsAndArrivalToApiFlightWithSplits(flight, vps)
          case _: FlightNotFound =>
            log.info(s"notgot splits for ${flight}")
            ApiFlightWithSplits(flight, calcCsvApiSplits(flight))
        }
        futureResp
      case None =>
        log.info(s"couldnot parse IATA for ${flight}")
        //todo this was supposed to be an Either!
        Future.successful(ApiFlightWithSplits(flight, Nil))
    }
  }

  def splitsAndArrivalToApiFlightWithSplits(flight: Arrival, vps: VoyagePaxSplits): ApiFlightWithSplits = {
    log.info(s"didgot splits ${vps} for ${flight}")

    val csvSplits = csvSplitsProvider(flight)
    log.info(s"flight: $flight csvSplits are $csvSplits")

    val egatePercentage = CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplits, 0.6)
    val fastTrackPercentages: FastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplits, 0d, 0d)
    val voyagePaxSplitsWithEgatePercentage = CSVPassengerSplitsProvider.applyEgates(vps, egatePercentage)
    val withFastTrackPercentages = CSVPassengerSplitsProvider.applyFastTrack(voyagePaxSplitsWithEgatePercentage, fastTrackPercentages)

    log.info(s"applied egate percentage $egatePercentage toget $voyagePaxSplitsWithEgatePercentage")
    log.info(s"applied fasttrack percentage $fastTrackPercentages toget $withFastTrackPercentages")

    val apiSplits: List[ApiSplits] = List(
      ApiSplits(
        withFastTrackPercentages.paxSplits.map(s => ApiPaxTypeAndQueueCount(s.passengerType, s.queueType, s.paxCount)),
        ApiSplitsWithCsvPercentage)
    ) ::: calcCsvApiSplits(flight)

    ApiFlightWithSplits(flight, apiSplits)
  }

  def flightStateSnapshotMessageFromState: FlightStateSnapshotMessage = {
    FlightStateSnapshotMessage(
      flightState.map { case (_, f) => apiFlightToFlightMessage(f) }.toSeq,
      lastKnownPaxState.map { case (key, pax) => FlightLastKnownPaxMessage(Option(key), Option(pax)) }.toSeq
    )
  }

  private def calcCsvApiSplits(flight: Arrival): List[ApiSplits] = {
    val csvSplits: Option[SplitRatiosNs.SplitRatios] = csvSplitsProvider(flight)

    val apiPaxAndQueueRatios: Option[List[ApiPaxTypeAndQueueCount]] = csvSplits.map(s => s.splits.map(sr => ApiPaxTypeAndQueueCount(sr.paxType.passengerType, sr.paxType.queueType, sr.ratio * 100)))
    val toList: List[ApiPaxTypeAndQueueCount] = apiPaxAndQueueRatios.toList.flatten
    csvSplits.map(csvSplit => ApiSplits(toList, csvSplit.origin, splitStyle = Percentage)).toList
  }

  private def requestCrunch(newFlights: List[Arrival]) = {
    if (newFlights.nonEmpty)
      crunchActorRef ! PerformCrunchOnFlights(newFlights)
  }

  def ApiFlightToArrival(f: ApiFlight): Arrival = {
    Arrival(
      f.Operator,
      f.Status,
      f.EstDT,
      f.ActDT,
      f.EstChoxDT,
      f.ActChoxDT,
      f.Gate,
      f.Stand,
      f.MaxPax,
      f.ActPax,
      f.TranPax,
      f.RunwayID,
      f.BaggageReclaimId,
      f.FlightID,
      f.AirportID,
      f.Terminal,
      f.rawICAO,
      f.rawIATA,
      f.Origin,
      f.SchDT,
      f.PcpTime,
      None
    )
  }
}
