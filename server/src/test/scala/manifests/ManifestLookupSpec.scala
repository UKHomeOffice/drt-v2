package manifests

import manifests.passengers.BestAvailableManifest
import org.specs2.specification.Before
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ CarrierCode, VoyageNumber }
import uk.gov.homeoffice.drt.db.AggregateDbH2
import uk.gov.homeoffice.drt.db.tables.{ ProcessedJsonRow, ProcessedZipRow, VoyageManifestPassengerInfoRow }
import uk.gov.homeoffice.drt.models.DocumentType.Passport
import uk.gov.homeoffice.drt.models.{ ManifestPassengerProfile, UniqueArrivalKey }
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.{ PaxAge, PortCode }
import uk.gov.homeoffice.drt.time.SDate

import java.sql.Timestamp
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ManifestLookupSpec extends CrunchTestLike with Before {
  override def before: Any = {
    AggregateDbH2.dropAndCreateH2Tables()
    val scheduled = SDate("2024-05-15T12:00Z")
    val processedZipRow =
      ProcessedZipRow("some-zip", true, new Timestamp(scheduled.millisSinceEpoch), Option("2024-05-15"))
    val processedJsonRow = ProcessedJsonRow(
      zip_file_name = "some-zip",
      json_file_name = "some-json",
      suspicious_date = false,
      success = true,
      processed_at = new Timestamp(scheduled.millisSinceEpoch),
      arrival_port_code = Option("LHR"),
      departure_port_code = Option("JFK"),
      voyage_number = Option(1),
      carrier_code = Option("BA"),
      scheduled = Option(new Timestamp(scheduled.millisSinceEpoch)),
      event_code = Option("DC"),
      non_interactive_total_count = Option(0),
      non_interactive_trans_count = Option(0),
      interactive_total_count = Option(1),
      interactive_trans_count = Option(0)
    )
    val passengerRow = VoyageManifestPassengerInfoRow(
      event_code = "DC",
      arrival_port_code = "LHR",
      departure_port_code = "JFK",
      voyage_number = 1,
      carrier_code = "BA",
      scheduled_date = new Timestamp(scheduled.millisSinceEpoch),
      day_of_week = 3,
      week_of_year = 20,
      document_type = "P",
      document_issuing_country_code = "GB",
      eea_flag = "EEA",
      age = 30,
      disembarkation_port_code = "LHR",
      in_transit_flag = "N",
      disembarkation_port_country_code = "GBR",
      nationality_country_code = "GBR",
      passenger_identifier = "1",
      in_transit = false,
      json_file = "some-json"
    )
    import AggregateDbH2.profile.api._
    Await.ready(
      AggregateDbH2.run(DBIO.seq(
        AggregateDbH2.processedZip += processedZipRow,
        AggregateDbH2.processedJson += processedJsonRow,
        AggregateDbH2.voyageManifestPassengerInfo += passengerRow
      )),
      1.second
    )
  }

  "ManifestLookup" should {
    "classify historic passengers without treating blank disembarkation countries as transit" in {
      import AggregateDbH2.profile.api._

      val existing = Await.result(AggregateDbH2.run(AggregateDbH2.voyageManifestPassengerInfo.result), 1.second).head
      val passengers = Seq(
        existing.copy(passenger_identifier = "blank", disembarkation_port_country_code = ""),
        existing.copy(passenger_identifier = "whitespace", disembarkation_port_country_code = "  "),
        existing.copy(passenger_identifier = "uk", disembarkation_port_country_code = "GBR"),
        existing.copy(passenger_identifier = "abroad", disembarkation_port_country_code = "FRA"),
        existing.copy(passenger_identifier = "flag", disembarkation_port_country_code = "", in_transit_flag = "Y"),
        existing.copy(passenger_identifier = "stored", disembarkation_port_country_code = "", in_transit = true)
      )
      Await.result(AggregateDbH2.run(AggregateDbH2.voyageManifestPassengerInfo ++= passengers), 1.second)

      val scheduled = new Timestamp(SDate("2024-05-15T12:00Z").millisSinceEpoch)
      val profiles = Await.result(
        ManifestLookup(AggregateDbH2).paxForArrivalsQuery(Vector(("LHR", "JFK", "1", scheduled))),
        1.second
      )

      profiles.flatMap(p => p.passengerIdentifier.map(_ -> p.inTransit)).toMap === Map(
        "1" -> false,
        "blank" -> false,
        "whitespace" -> false,
        "uk" -> false,
        "abroad" -> true,
        "flag" -> true,
        "stored" -> true
      )
    }

    "lookup a manifest" in {
      skipped("Postgres queries incompatible with H2")

      val manifestLookup = ManifestLookup(AggregateDbH2)
      val manifest = manifestLookup.maybeBestAvailableManifest(
        PortCode("LHR"),
        PortCode("JFK"),
        VoyageNumber(1),
        SDate("2024-05-15T12:00Z")
      )
      val expectedKey = UniqueArrivalKey(PortCode("LHR"), PortCode("JFK"), VoyageNumber(1), SDate("2024-05-15T12:00Z"))
      val expectedManifest = BestAvailableManifest(
        source = Historical,
        arrivalPortCode = PortCode("LHR"),
        departurePortCode = PortCode("JFK"),
        voyageNumber = VoyageNumber(1),
        carrierCode = CarrierCode("BA"),
        scheduled = SDate("2024-05-15T12:00Z"),
        nonUniquePassengers = Seq(
          ManifestPassengerProfile(
            nationality = Nationality("GBR"),
            documentType = Option(Passport),
            age = Option(PaxAge(30)),
            inTransit = false,
            passengerIdentifier = Option("1")
          )
        ),
        maybeEventType = Option(DC)
      )
      Await.result(manifest, 1.second) === (expectedKey, Option(expectedManifest))
    }
  }
}
