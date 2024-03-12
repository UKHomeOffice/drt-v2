package controllers.application

import drt.shared.ArrivalGenerator
import module.DRTModule
import play.api.test.Helpers
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.Passengers
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.{AirportConfig, LiveFeedSource}
import uk.gov.homeoffice.drt.ports.config.Stn
import uk.gov.homeoffice.drt.time.SDate

class PortStateControllerSpec extends CrunchTestLike {
  "Given a PortStateController" >> {
    "When I import a live arrival and its live API manifest" >> {
      "Then I should eventually see the arrival with live splits in the crunch updates" >> {
        val drtInterface = newDrtInterface
        val controller = newController(drtInterface)

        val scheduled = SDate("2021-01-01T00:00")
        val arrival = ArrivalGenerator.arrival("BA0001", sch = scheduled.millisSinceEpoch,
          feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(Option(100), None)))
        drtInterface.applicationService.run.map {
          case Some(cs) =>
            cs.liveArrivalsResponse.feedSource
        }
      }
    }
  }

  private def newController(interface: DrtSystemInterface) =
    new PortStateController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface =
    new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val airportConfig: AirportConfig = Stn.config
    }.provideDrtSystemInterface
}
