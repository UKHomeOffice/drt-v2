package manifests

import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.specs2.specification.Before
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType.Passport
import services.crunch.{CrunchTestLike, H2Tables}
import slickdb.{ProcessedJsonRow, ProcessedZipRow, VoyageManifestPassengerInfoRow}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, VoyageNumber}
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.time.SDate

import java.sql.Timestamp
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ManifestLookupSpec extends CrunchTestLike with Before {
  override def before: Any = {
    H2Tables.dropAndCreateH2Tables()
    val scheduled = SDate("2024-05-15T12:00Z")
    val processedZipRow = ProcessedZipRow("some-zip", true, new Timestamp(scheduled.millisSinceEpoch), Option("2024-05-15"))
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
      interactive_trans_count = Option(0),
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
      json_file = "some-json",
    )
    import H2Tables.profile.api._
    Await.ready(
      H2Tables.run(DBIO.seq(
        H2Tables.ProcessedZip += processedZipRow,
        H2Tables.ProcessedJson += processedJsonRow,
        H2Tables.VoyageManifestPassengerInfo += passengerRow,
      )), 1.second)
  }

  "ManifestLookup" should {
    "lookup a manifest" in {
      skipped("Postgres queries incompatible with H2")

      val manifestLookup = ManifestLookup(H2Tables)
      val manifest = manifestLookup.maybeBestAvailableManifest(PortCode("LHR"), PortCode("JFK"), VoyageNumber(1), SDate("2024-05-15T12:00Z"))
      val expectedKey = UniqueArrivalKey(PortCode("LHR"), PortCode("JFK"), VoyageNumber(1), SDate("2024-05-15T12:00Z"))
      val expectedManifest = BestAvailableManifest(
        source = Historical,
        arrivalPortCode = PortCode("LHR"),
        departurePortCode = PortCode("JFK"),
        voyageNumber = VoyageNumber(1),
        carrierCode = CarrierCode("BA"),
        scheduled = SDate("2024-05-15T12:00Z"),
        nonUniquePassengers = Seq(
          ManifestPassengerProfile(nationality = Nationality("GBR"), documentType = Option(Passport),
            age = Option(PaxAge(30)), inTransit = false, passengerIdentifier = Option("1"))
        ),
        maybeEventType = Option(DC)
      )
      Await.result(manifest, 1.second) === (expectedKey, Option(expectedManifest))
    }
  }
}
