package queueus

import manifests.passengers.ManifestPassengerProfile
import passengersplits.core.PassengerTypeCalculator.{isB5JPlus, isEea, isVisaNational}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import uk.gov.homeoffice.drt.ports.PaxType
import uk.gov.homeoffice.drt.ports.PaxTypes._

trait PaxTypeAllocator {
  val b5JPlus: PartialFunction[ManifestPassengerProfile, PaxType] = {
    case ManifestPassengerProfile(country, _, Some(age), _, _) if isB5JPlus(country) && age.isUnder(12) => B5JPlusNationalBelowEGateAge
    case ManifestPassengerProfile(country, _, _, _, _) if isB5JPlus(country) => B5JPlusNational
  }

  val countryAndDocumentTypes: PartialFunction[ManifestPassengerProfile, PaxType] = {
    case ManifestPassengerProfile(country, Some(docType), Some(age), _, _) if isEea(country) && docType == DocumentType.Passport && age.isUnder(12) => EeaBelowEGateAge
    case ManifestPassengerProfile(country, Some(docType), _, _, _) if isEea(country) && docType == DocumentType.Passport => EeaMachineReadable
    case ManifestPassengerProfile(country, _, _, _, _) if isEea(country) => EeaNonMachineReadable
    case ManifestPassengerProfile(country, _, _, _, _) if !isEea(country) && isVisaNational(country) => VisaNational
    case ManifestPassengerProfile(country, _, _, _, _) if !isEea(country) => NonVisaNational
  }

  val transit: PartialFunction[ManifestPassengerProfile, PaxType] = {
    case ManifestPassengerProfile(_, _, _, Some(isTransit), _) if isTransit => Transit
  }

  val withTransit: PartialFunction[ManifestPassengerProfile, PaxType] = transit orElse countryAndDocumentTypes

  val noTransit: PartialFunction[ManifestPassengerProfile, PaxType] = countryAndDocumentTypes

  def apply(manifestPassengerProfile: ManifestPassengerProfile): PaxType
}

case object DefaultPaxTypeAllocator extends PaxTypeAllocator {
  override def apply(manifestPassengerProfile: ManifestPassengerProfile): PaxType =
    noTransit(manifestPassengerProfile)
}

case object DefaultWithTransitPaxTypeAllocator extends PaxTypeAllocator {
  override def apply(manifestPassengerProfile: ManifestPassengerProfile): PaxType = withTransit(manifestPassengerProfile)
}

case object B5JPlusTypeAllocator extends PaxTypeAllocator {
  val withB5JPlus: PartialFunction[ManifestPassengerProfile, PaxType] = b5JPlus orElse countryAndDocumentTypes

  override def apply(manifestPassengerProfile: ManifestPassengerProfile): PaxType =
    withB5JPlus(manifestPassengerProfile)
}

case object B5JPlusWithTransitTypeAllocator extends PaxTypeAllocator {
  val withTransitAndB5JPlus: PartialFunction[ManifestPassengerProfile, PaxType] = transit orElse b5JPlus orElse countryAndDocumentTypes

  override def apply(manifestPassengerProfile: ManifestPassengerProfile): PaxType =
    withTransitAndB5JPlus(manifestPassengerProfile)
}
