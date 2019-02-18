package manifests.passengers

import drt.shared.SDateLike

case class BestAvailableManifest(source: String,
                                 arrivalPortCode: String,
                                 departurePortCode: String,
                                 voyageNumber: String,
                                 carrierCode: String,
                                 scheduled: SDateLike,
                                 passengerList: List[ManifestPassengerProfile])

case class ManifestPassengerProfile(nationality: String,
                                    documentType: Option[String],
                                    age: Option[Int],
                                    inTransit: Option[Boolean])

