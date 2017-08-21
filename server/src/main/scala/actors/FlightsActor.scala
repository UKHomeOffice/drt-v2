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
import org.joda.time.{DateTime, DateTimeZone, LocalDate}
import org.joda.time.format.DateTimeFormat
import passengersplits.core.PassengerInfoRouterActor.{FlushOldVoyageManifests, ReportVoyagePaxSplit}
import server.protobuf.messages.FlightsMessage.{FlightLastKnownPaxMessage, FlightMessage, FlightStateSnapshotMessage, FlightsMessage}
import services.Crunch.{CrunchFlights, PublisherLike}
import services.SplitsProvider.SplitProvider
import services.{CSVPassengerSplitsProvider, Crunch, FastTrackPercentages, SDate}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

case object GetFlights

case object GetFlightsWithSplits

class FlightsActor(crunchStateActor: ActorRef,
                   dqApiSplitsActorRef: ActorRef,
                   crunchPublisher: PublisherLike,
                   csvSplitsProvider: SplitProvider,
                   _bestPax: (Arrival) => Int,
                   pcpArrivalTimeForFlight: (Arrival) => MilliDate,
                   airportConfig: AirportConfig)
  extends PersistentActor
    with ActorLogging
    with FlightState
    with DomesticPortList {
  implicit val timeout = Timeout(5 seconds)
  log.debug(s"flightsActorSplits: ${airportConfig.defaultPaxSplits} ${pprint.stringify(airportConfig)}")

  override def bestPax(a: Arrival): Int = _bestPax(a)

  import SplitRatiosNs.SplitSources._

  val snapshotInterval = 100

  val dqApiSplitsAskableActorRef: AskableActorRef = dqApiSplitsActorRef

  override def persistenceId = "flights-store"

  override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    Recovery
    super.onRecoveryFailure(cause, event)
    log.error(cause, "recovery failed in flightsActors")
  }

  val receiveRecover: Receive = {
    case FlightsMessage(recoveredFlights, createdAt) if recoveredFlights.length > 0 =>
      log.info(s"Recovering ${recoveredFlights.length} flights")
      consumeFlights(recoveredFlights.map(flightMessageToApiFlight).toList, retentionCutoff)
      dqApiSplitsActorRef ! FlushOldVoyageManifests(retentionCutoff.addDays(-1))

    case FlightsMessage(recoveredFlights, createdAt) if recoveredFlights.length == 0 =>
      log.info(s"No flight updates")

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
      log.info("Flights recovery completed")

    case message => log.error(s"unhandled message - $message")
  }

  private def lastMidnight = {
    LocalDate.now().toString(DateTimeFormat.forPattern("yyyy-MM-dd"))
  }

  private def lastMidnightMillis = {
    SDate(s"${lastMidnight}T00:00Z").millisSinceEpoch
  }

  val receiveCommand: Receive = LoggingReceive {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flightState.size}")
      sender ! Flights(flightState.values.toList)

    case GetFlightsWithSplits =>
      log.info(s"Being asked for flights with splits and I know about ${flightState.size}")
      val startTime = org.joda.time.DateTime.now()
      val replyTo = sender()
      val flights = flightsWithSplits
      flights.onSuccess {
        case s =>
          val replyingAt = org.joda.time.DateTime.now()
          val delta = replyingAt.getMillis - startTime.getMillis
          log.info(s"gathering flights wih splits to send to crunch state actor took ${delta}ms")
          replyTo ! FlightsWithSplits(s.toList)
      }

    case Flights(updatedFlights) if updatedFlights.nonEmpty =>
      val flightsWithLastKnownPax: List[Arrival] = consumeFlights(updatedFlights, retentionCutoff)
      dqApiSplitsActorRef ! FlushOldVoyageManifests(retentionCutoff.addDays(-1))

      if (flightsWithLastKnownPax.nonEmpty) {
        persistFlights(FlightsMessage(
          flightMessages = flightsWithLastKnownPax.map(flight => apiFlightToFlightMessage(flight)),
          createdAt = Option(SDate.now().millisSinceEpoch)
        ))
        crunchFlightsWithSplits
      }

    case Flights(updatedFlights) if updatedFlights.isEmpty =>
      log.info(s"No flight updates")

    case SaveSnapshotSuccess(md) =>
      log.info(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Snapshot failed $md\n$cause")

    case message => log.error("Actor saw unexpected message: " + message.toString)

  }

  private def crunchFlightsWithSplits = {
    val startTime = org.joda.time.DateTime.now()
    val flights = flightsWithSplits
    flights.onSuccess {
      case s =>
        val replyingAt = org.joda.time.DateTime.now()
        val delta = replyingAt.getMillis - startTime.getMillis
        log.info(s"gathering flights wih splits to send to crunch state actor took ${delta}ms")

        val localNow = SDate(new DateTime(DateTimeZone.forID("Europe/London")).getMillis)
        val crunchStartMillis = Crunch.getLocalLastMidnight(localNow).millisSinceEpoch
        val crunchEndMillis = crunchStartMillis + (1439 * 60000)

        log.info(s"crunchStartMillis: $crunchStartMillis")

        crunchPublisher.publish(CrunchFlights(s.toList, crunchStartMillis, crunchEndMillis))
    }
  }


  def flightsWithSplits: Future[Seq[ApiFlightWithSplits]] = {
    val apiFlights = flightState.values.toList
    val allSplitRequests: Seq[Future[ApiFlightWithSplits]] = apiFlights.map(addSplitsToArrival)

    val futureOfSeq: Future[Seq[ApiFlightWithSplits]] = Future.sequence(allSplitRequests)
    futureOfSeq.onFailure {
      case t =>
        log.error(t, s"Failed retrieving all splits for ${allSplitRequests.length} flights")
      //todo should we return a failure, or a partial list, here
    }

    futureOfSeq
  }

  def consumeFlights(flights: List[Arrival], dropFlightsBefore: SDateLike): List[Arrival] = {
    val flightsWithPcpTime = flights.map(f => f.copy(PcpTime = pcpArrivalTimeForFlight(f).millisSinceEpoch))
    val flightsWithLastKnownPax = addLastKnownPaxNos(flightsWithPcpTime)
    storeLastKnownPaxForFlights(flightsWithLastKnownPax)

    log.info(s"${flightsWithLastKnownPax.length} flight updates")

    onFlightUpdates(flightsWithLastKnownPax, dropFlightsBefore, domesticPorts)
    log.debug(s"flight state now contains ${flightState.values.toList}")

    flightsWithLastKnownPax
  }

  def persistFlights(flightsMessage: FlightsMessage) = {
    persist(flightsMessage) { (event: FlightsMessage) =>
      log.info(s"persisting ${flightsMessage.flightMessages.length} flights")
      context.system.eventStream.publish(event)
      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info("saving flights snapshot")
        saveSnapshot(flightStateSnapshotMessageFromState)
      }
    }
  }

  def addSplitsToArrival(flight: Arrival): Future[ApiFlightWithSplits] = {
    FlightParsing.parseIataToCarrierCodeVoyageNumber(flight.IATA) match {
      case Some((_, voyageNumber)) =>
        val scheduledDate = SDate(flight.SchDT, DateTimeZone.UTC)
        val splitsRequest = ReportVoyagePaxSplit(flight.AirportID, flight.Operator, voyageNumber, scheduledDate)
        val future = dqApiSplitsAskableActorRef ? splitsRequest
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
        val portSplits = airportConfig.defaultPaxSplits
        val httpApiPortSplits = splitRatiosToApiSplits(portSplits)
        Future.successful(ApiFlightWithSplits(flight, httpApiPortSplits :: Nil))
    }
  }

  def splitsAndArrivalToApiFlightWithSplits(flight: Arrival, vps: VoyagePaxSplits): ApiFlightWithSplits = {
    log.info(s"didgot splits ${vps} for ${flight}")

    val csvSplits = csvSplitsProvider(flight)
    log.debug(s"flight: $flight csvSplits are $csvSplits")

    val egatePercentage = CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplits, 0.6)
    val fastTrackPercentages: FastTrackPercentages = CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplits, 0d, 0d)
    val voyagePaxSplitsWithEgatePercentage = CSVPassengerSplitsProvider.applyEgates(vps, egatePercentage)
    val withFastTrackPercentages = CSVPassengerSplitsProvider.applyFastTrack(voyagePaxSplitsWithEgatePercentage, fastTrackPercentages)

    log.debug(s"applied egate percentage $egatePercentage toget $voyagePaxSplitsWithEgatePercentage")
    log.debug(s"applied fasttrack percentage $fastTrackPercentages toget $withFastTrackPercentages")

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

  val defaultSplits = splitRatiosToApiSplits(airportConfig.defaultPaxSplits)

  private def calcCsvApiSplits(flight: Arrival): List[ApiSplits] = {
    val splits = csvSplitsProvider(flight).map(csvSplit => {
      splitRatiosToApiSplits(csvSplit)
    }).toList ::: defaultSplits :: Nil
    splits
  }

  def splitRatiosToApiSplits(csvSplit: SplitRatiosNs.SplitRatios) = {
    val toList: List[ApiPaxTypeAndQueueCount] = csvSplit.splits.map(sr => ApiPaxTypeAndQueueCount(sr.paxType.passengerType, sr.paxType.queueType, sr.ratio * 100))
    ApiSplits(toList, csvSplit.origin, splitStyle = Percentage)
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
