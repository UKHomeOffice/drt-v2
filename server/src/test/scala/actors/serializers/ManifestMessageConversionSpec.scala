package actors.serializers

import drt.shared._
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.protobuf.messages.VoyageManifest.PassengerInfoJsonMessage

class ManifestMessageConversionSpec extends Specification {

  "Given a VoyageManifest if I serialise it and de-serialise it it should be the same" >> {
    val manifest = VoyageManifests(Set(VoyageManifest(
      EventCode = EventType("DC"),
      ArrivalPortCode = PortCode("TST"),
      DeparturePortCode = PortCode("AMS"),
      VoyageNumber = VoyageNumber(123),
      CarrierCode = CarrierCode("TS"),
      ScheduledDateOfArrival = ManifestDateOfArrival("2020-09-07"),
      ScheduledTimeOfArrival = ManifestTimeOfArrival("09:30:00"),
      PassengerList = List(PassengerInfoJson(
        DocumentType = Option(DocumentType("P")),
        DocumentIssuingCountryCode = Nationality("GBR"),
        EEAFlag = EeaFlag("EEA"),
        Age = Option(PaxAge(30)),
        DisembarkationPortCode = Option(PortCode("TST")),
        InTransitFlag = InTransit("N"),
        DisembarkationPortCountryCode = Some(Nationality("TST")),
        NationalityCountryCode = Some(Nationality("GBR")),
        PassengerIdentifier = Option("id")
      )))
    ))

    val serialised = ManifestMessageConversion.voyageManifestsToMessage(manifest)

    val result = ManifestMessageConversion.voyageManifestsFromMessage(serialised)

    result === manifest
  }
  "Given a VoyageManifest that's been serialized with a bad Nationality toString method " +
    "then I should get the nationality without the word Nationality in there" >> {
    val passengerMessage = PassengerInfoJsonMessage(
      Some("P"),
      Some("Nationality(GBR)"),
      Some("EEA"),
      Some("30"),
      Some("TST"),
      Some("N"),
      Some("Nationality(TST)"),
      Some("Nationality(GBR)"),
      Some("id")
    )

    val expected = PassengerInfoJson(
      DocumentType = Option(DocumentType("P")),
      DocumentIssuingCountryCode = Nationality("GBR"),
      EEAFlag = EeaFlag("EEA"),
      Age = Option(PaxAge(30)),
      DisembarkationPortCode = Option(PortCode("TST")),
      InTransitFlag = InTransit("N"),
      DisembarkationPortCountryCode = Some(Nationality("TST")),
      NationalityCountryCode = Some(Nationality("GBR")),
      PassengerIdentifier = Option("id")
    )


    val result = ManifestMessageConversion.passengerInfoFromMessage(passengerMessage)

    result === expected
  }


}
