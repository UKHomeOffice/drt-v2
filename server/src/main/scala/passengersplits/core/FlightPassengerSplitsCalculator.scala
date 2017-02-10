package passengersplits.core

import passengersplits.parsing.PassengerInfoParser.PassengerInfo

object PassengerSplitsCalculator {
  val passport: Some[String] = Some("P")
  val identityCard: Some[String] = Some("I")

  case class PaxSplits(egate: Double = 0.0, eea: Double = 0.0, nonEea: Double = 0.0, fastTrack: Double = 0.0) {
    val gateTypeTotal = eea + nonEea + fastTrack + egate

    def normalised = this.copy(egate = this.egate / gateTypeTotal,
      eea = this.eea / gateTypeTotal,
      nonEea = this.nonEea / gateTypeTotal,
      fastTrack = this.fastTrack / gateTypeTotal
    )
  }

  val eeaCountryCodes: List[String] = "GB" :: "DE" :: Nil

  def isEEA(pi: PassengerInfo) = eeaCountryCodes contains pi.DocumentIssuingCountryCode

  def flightPaxSplits(passengerInfos: List[PassengerInfo]): PaxSplits = {

    val passportCounts = passengerInfos.foldLeft(PaxSplits()) {
      (counts, pi) => {
        pi match {
          case pi if isEEA(pi) && pi.DocumentType == passport => counts.copy(egate = counts.egate + 1)
          case pi if pi.DocumentIssuingCountryCode == "DE" => counts.copy(eea = counts.eea + 1)
          case pi if pi.DocumentIssuingCountryCode == "NZ" => counts.copy(nonEea = counts.nonEea + 1)
          case default => counts.copy(nonEea = counts.nonEea + 1)
        }
      }
    }
    passportCounts
  }
}
