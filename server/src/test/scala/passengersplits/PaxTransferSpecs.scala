package passengersplits

import org.specs2._
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfo, PassengerInfoJson, VoyageManifest}

class PaxTransferSpecs extends Specification {
  def is =
    s2"""
    This is a specification of passenger splits with transfer passengers from the DQ Advance Passenger Info (API) feed

        |As an LHR SO user of DRT I would like to see the transfer passengers that are arriving for a flight.
        |The transfer passengers should be removed from the API pax numbers arriving at the PCP and displayed separately in a Transfer column.
        |This will ensure that I know the number of passengers that should be seen at PCP from a flight,
        |it will also highlight the number of additional passengers that could be seen by the
        |PCP if the arrival of the flight is delayed and the passengers miss their connecting flight.
        |
        |opt - It would be useful to see the number of international and domestic transfers - hover over could be used?
        |
        |$transferPaxAreRemovedFromSplits
        |
        |$transferPaxCanBeRetrievedIndependently
        |
    """

  def transferPaxAreRemovedFromSplits = {

    def e1 = {
      val vpi = VoyageManifest(EventCodes.CheckIn, "LHR", "___", "123", "BA", "2017/05/12", "10:15",
        PassengerInfoJson(Some("P"), "GBR", "EEA", Some("23"),
          DisembarkationPortCode = Some("LHR"), InTransitFlag = "N", DisembarkationPortCountryCode = Some("GBR"),
          NationalityCountryCode = None) ::
          PassengerInfoJson(Some("P"), DocumentIssuingCountryCode = "GBR", EEAFlag = "EEA", Age = Some("45"),
            DisembarkationPortCode = Some("COL"), InTransitFlag = "Y", DisembarkationPortCountryCode = Some("MNP"),
            NationalityCountryCode = None) :: Nil
      )


      pending("what?")
    }
    s2"""
    Given A Flight, with 2 pax on it, and 1 of them is a transfer passenger,
    When we calculate the splits,
    Then we do NOT see them in the split counts
    $e1
    """
  }

  def transferPaxCanBeRetrievedIndependently = pending("tbd")
}
