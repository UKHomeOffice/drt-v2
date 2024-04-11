package controllers.application

import actors.DrtParameters
import drt.shared.airportconfig.Test
import module.DrtModule
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters

class TestDrtModule(override val airportConfig: AirportConfig = Test.config) extends DrtModule {
  override lazy val drtParameters: DrtParameters = MockDrtParameters()
}
