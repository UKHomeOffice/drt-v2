package actors.serializers

import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.protobuf.messages.VoyageManifest.{PassengerInfoJsonMessage, VoyageManifestMessage, VoyageManifestsMessage}
import services.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}

import scala.util.Try

object ManifestMessageConversion {

  def passengerInfoFromMessage(m: PassengerInfoJsonMessage): PassengerInfoJson = PassengerInfoJson(
    DocumentType = m.documentType.map(DocumentType(_)),
    DocumentIssuingCountryCode = m.documentIssuingCountryCode
      .map(n => Nationality(correctNationalityBug(n))).getOrElse(Nationality("")),
    EEAFlag = EeaFlag(m.eeaFlag.getOrElse("")),
    Age = m.age.flatMap(ageString => Try(ageString.toInt).toOption).map(a => PaxAge(a)),
    DisembarkationPortCode = m.disembarkationPortCode.map(PortCode(_)),
    InTransitFlag = InTransit(m.inTransitFlag.getOrElse("")),
    DisembarkationPortCountryCode = m.disembarkationPortCountryCode.map(n => Nationality(correctNationalityBug(n))),
    NationalityCountryCode = m.nationalityCountryCode.map(n => Nationality(correctNationalityBug(n))),
    PassengerIdentifier = m.passengerIdentifier
  )

  def correctNationalityBug(nationality: String): String =
    nationality
      .replace("Nationality(", "").replace(")", "")

  def voyageManifestFromMessage(m: VoyageManifestMessage): VoyageManifest = {
    VoyageManifest(
      EventCode = EventType(m.eventCode.getOrElse("")),
      ArrivalPortCode = PortCode(m.arrivalPortCode.getOrElse("")),
      DeparturePortCode = PortCode(m.departurePortCode.getOrElse("")),
      VoyageNumber = VoyageNumber(m.voyageNumber.getOrElse("")),
      CarrierCode = CarrierCode(m.carrierCode.getOrElse("")),
      ScheduledDateOfArrival = ManifestDateOfArrival(m.scheduledDateOfArrival.getOrElse("")),
      ScheduledTimeOfArrival = ManifestTimeOfArrival(m.scheduledTimeOfArrival.getOrElse("")),
      PassengerList = m.passengerList.toList.map(passengerInfoFromMessage)
    )
  }

  def voyageManifestsFromMessage(vmms: VoyageManifestsMessage): VoyageManifests = VoyageManifests(
    vmms.manifestMessages.map(voyageManifestFromMessage).toSet
  )

  def voyageManifestsToMessage(vms: VoyageManifests): VoyageManifestsMessage = VoyageManifestsMessage(
    Option(SDate.now().millisSinceEpoch),
    vms.manifests.map(voyageManifestToMessage).toSeq
  )

  def voyageManifestToMessage(vm: VoyageManifest): VoyageManifestMessage = {
    VoyageManifestMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      eventCode = Option(vm.EventCode.toString),
      arrivalPortCode = Option(vm.ArrivalPortCode.iata),
      departurePortCode = Option(vm.DeparturePortCode.iata),
      voyageNumber = Option(vm.VoyageNumber.toString),
      carrierCode = Option(vm.CarrierCode.code),
      scheduledDateOfArrival = Option(vm.ScheduledDateOfArrival.date),
      scheduledTimeOfArrival = Option(vm.ScheduledTimeOfArrival.time),
      passengerList = vm.PassengerList.map(passengerInfoToMessage)
    )
  }

  def passengerInfoToMessage(pi: PassengerInfoJson): PassengerInfoJsonMessage = {
    PassengerInfoJsonMessage(
      documentType = pi.DocumentType.map(_.toString),
      documentIssuingCountryCode = Option(pi.DocumentIssuingCountryCode.toString),
      eeaFlag = Option(pi.EEAFlag.value),
      age = pi.Age.map(_.toString),
      disembarkationPortCode = pi.DisembarkationPortCode.map(_.toString),
      inTransitFlag = Option(pi.InTransitFlag.toString),
      disembarkationPortCountryCode = pi.DisembarkationPortCountryCode.map(_.toString),
      nationalityCountryCode = pi.NationalityCountryCode.map(_.toString),
      passengerIdentifier = pi.PassengerIdentifier
    )
  }
}
