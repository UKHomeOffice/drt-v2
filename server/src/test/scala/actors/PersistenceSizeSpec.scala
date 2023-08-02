package actors

import akka.actor.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import drt.shared.DrtPortConfigs
import org.specs2.mutable.Specification
import play.api.Configuration
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.FlightsWithSplitsDiffMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{flightWithSplitsFromMessage, uniqueArrivalsFromMessages}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class PersistenceSizeSpec extends Specification {
  implicit val system: ActorSystem = ActorSystem("PersistenceSizeSpec", ConfigFactory.load("application-acp.conf"))
  implicit val mat: Materializer = Materializer.createMaterializer(system)
  implicit val ec: ExecutionContext = system.dispatcher
  //  implicit val timeout: akka.util.Timeout = new akka.util.Timeout(5.seconds)

  val portCode: PortCode = PortCode(ConfigFactory.load().getString("portcode").toUpperCase)
  //  val airportConfig = DrtPortConfigs.confByPort(portCode)
  //  val config: Configuration = new Configuration(ConfigFactory.load)
  //  ProdDrtSystem(airportConfig, ProdDrtParameters(config))

  // To do: use BaseByteArrayJournalDao and other legacy classes/tools to read & write to journal
  //  JournalRow is a case class with 5 fields: persistenceId, sequenceNumber, deleted, tags, message
  //  message is a byte array of a PersistentRepr which is then converted into an EventEnvelope

  "Given data for a day of arrival updates" >> {
    "I want to see which fields are updated and how often" >> {
      skipped("This test is for manual use only")
      val persistenceId = "terminal-flights-n-2023-07-01"
      val flights = mutable.Map.empty[UniqueArrival, ApiFlightWithSplits]
      var newCount = 0
      var splitsCount = 0
      var arrivalCount = 0
      var arrivalAndSplitsCount = 0
      var noChangeCount = 0
      val stream = PersistenceQuery(system)
        .readJournalFor[DbStreamingJournal.ReadJournalType](DbStreamingJournal.id)
        .currentEventsByPersistenceId(persistenceId, 1, Long.MaxValue)
        .map { envelope =>
          envelope.event match {
            case FlightsWithSplitsDiffMessage(createdAt, removals, updates) =>
              println(s"${envelope.sequenceNr} @ ${SDate(envelope.timestamp).toISOString} Got ${updates.size} updates and ${removals.size} removals created at ${createdAt.map(SDate(_).toISOString)}")
              uniqueArrivalsFromMessages(removals).collect {
                case uniqueArrival: UniqueArrival => flights.remove(uniqueArrival)
              }
              updates
                .map(flightWithSplitsFromMessage)
                .foreach { fws =>
                  var updatedFields = Set.empty[String]
                  flights.get(fws.unique) match {
                    case Some(old: ApiFlightWithSplits) =>
                      if (old.apiFlight.Operator != fws.apiFlight.Operator) updatedFields += "Operator"
                      if (old.apiFlight.CarrierCode != fws.apiFlight.CarrierCode) updatedFields += "CarrierCode"
                      if (old.apiFlight.VoyageNumber != fws.apiFlight.VoyageNumber) updatedFields += "VoyageNumber"
                      if (old.apiFlight.FlightCodeSuffix != fws.apiFlight.FlightCodeSuffix) updatedFields += "FlightCodeSuffix"
                      if (old.apiFlight.Status != fws.apiFlight.Status) updatedFields += "Status"
                      if (old.apiFlight.Estimated != fws.apiFlight.Estimated) updatedFields += "Estimated"
                      if (old.apiFlight.Predictions != fws.apiFlight.Predictions) updatedFields += "Predictions"
                      if (old.apiFlight.Actual != fws.apiFlight.Actual) updatedFields += "Actual"
                      if (old.apiFlight.EstimatedChox != fws.apiFlight.EstimatedChox) updatedFields += "EstimatedChox"
                      if (old.apiFlight.ActualChox != fws.apiFlight.ActualChox) updatedFields += "ActualChox"
                      if (old.apiFlight.Gate != fws.apiFlight.Gate) updatedFields += "Gate"
                      if (old.apiFlight.Stand != fws.apiFlight.Stand) updatedFields += "Stand"
                      if (old.apiFlight.MaxPax != fws.apiFlight.MaxPax) updatedFields += "MaxPax"
                      if (old.apiFlight.RunwayID != fws.apiFlight.RunwayID) updatedFields += "RunwayID"
                      if (old.apiFlight.BaggageReclaimId != fws.apiFlight.BaggageReclaimId) updatedFields += "BaggageReclaimId"
                      if (old.apiFlight.AirportID != fws.apiFlight.AirportID) updatedFields += "AirportID"
                      if (old.apiFlight.Terminal != fws.apiFlight.Terminal) updatedFields += "Terminal"
                      if (old.apiFlight.Origin != fws.apiFlight.Origin) updatedFields += "Origin"
                      if (old.apiFlight.Scheduled != fws.apiFlight.Scheduled) updatedFields += "Scheduled"
                      if (old.apiFlight.PcpTime != fws.apiFlight.PcpTime) updatedFields += "PcpTime"
                      if (old.apiFlight.FeedSources != fws.apiFlight.FeedSources) updatedFields += "FeedSources"
                      if (old.apiFlight.CarrierScheduled != fws.apiFlight.CarrierScheduled) updatedFields += "CarrierScheduled"
                      if (old.apiFlight.ScheduledDeparture != fws.apiFlight.ScheduledDeparture) updatedFields += "ScheduledDeparture"
                      if (old.apiFlight.RedListPax != fws.apiFlight.RedListPax) updatedFields += "RedListPax"
                      if (old.apiFlight.PassengerSources != fws.apiFlight.PassengerSources) updatedFields += "PassengerSources"
                      //                      if (old.lastUpdated != fws.lastUpdated) updatedFields += "lastUpdated"
                      if (updatedFields.nonEmpty) {
                        if (old.splits != fws.splits) {
//                          println(s"Splits and arrivals updated")
                          arrivalAndSplitsCount += 1
                        } else {
//                          println(s"Arrival updated fields: ${updatedFields.mkString(", ")}")
                          arrivalCount += 1
                        }
                      }
                      else {
                        if (old.apiFlight != fws.apiFlight)
                          println(s"No updated fields but not equal....")

                        if (old.splits != fws.splits) {
//                          println(s"Splits updated")
                          splitsCount += 1
                        }
                        noChangeCount += 1
                      }
                    case None =>
//                      println(s"New arrival")
                      newCount += 1
                  }
                  flights.put(fws.unique, fws)
                }
          }
        }
        .recover(e => println(s"Error: $e"))
        .runWith(Sink.ignore)
      Await.ready(stream, 1.minute)
      println(s"New: $newCount, Arrivals: $arrivalCount, Splits: $splitsCount, arrivals + splits: $arrivalAndSplitsCount, No change: $noChangeCount")

      success
    }
  }
}
