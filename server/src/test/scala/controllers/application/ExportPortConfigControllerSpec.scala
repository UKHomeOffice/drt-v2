package controllers.application

import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import play.api.test.Helpers._
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters

class ExportPortConfigControllerSpec extends PlaySpec {
  "ExportPortConfigController" should {
    "get port config" in {
      val controller: ExportPortConfigController = exportPortConfigController

      val result = controller.exportConfig.apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""E-gates schedule
           |Terminal,Effective from,OpenGates per bank
           |T1,01-01-2020 00:00,3 banks: 10/10 10/10 10/10
           |
           |Queue SLAs
           |Effective from,EGate,EeaDesk,NonEeaDesk
           |01-09-2014 00:00,5,25,45
           |
           |Desks and Egates
           |22 desks
           |30 egates in 3 banks: 10 10 10
           |
           |Processing Times
           |Passenger Type & Queue,Seconds
           |B5J+ National to e-Gates,45
           |EEA Machine Readable to e-Gates,45
           |GBR National to e-Gates,45
           |B5J+ National to EEA,50
           |B5J+ Child to EEA,50
           |EEA Child to EEA,33
           |EEA Machine Readable to EEA,33
           |EEA Non-Machine Readable to EEA,33
           |GBR National to EEA,26
           |GBR National Child to EEA,26
           |Non-Visa National to Non-EEA,75
           |Visa National to Non-EEA,89
           |
           |Passenger Queue Allocation
           |Passenger Type,Queue,Allocation
           |B5J+ National,e-Gates,70%
           |B5J+ National,EEA,30%
           |B5J+ Child,EEA,100%
           |EEA Child,EEA,100%
           |EEA Machine Readable,e-Gates,80%
           |EEA Machine Readable,EEA,20%
           |EEA Non-Machine Readable,EEA,100%
           |GBR National,e-Gates,80%
           |GBR National,EEA,20%
           |GBR National Child,EEA,100%
           |Non-Visa National,Non-EEA,100%
           |Visa National,Non-EEA,100%
           |
           |Walk times
           |Default walk time (minutes),10
           |
           |Gate/Stand Walk time
           |Gate, Walk time in minutes
           |1,2
           |Stand, Walk time in minutes
           |03A,4""".stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }

  private def exportPortConfigController = {
    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      lazy override val mockDrtParameters: MockDrtParameters = new MockDrtParameters() {
        override val gateWalkTimesFilePath = Some(getClass.getClassLoader.getResource("gate-walk-time-test.csv").getPath)
        override val standWalkTimesFilePath = Some(getClass.getClassLoader.getResource("stand-walk-time-test.csv").getPath)
      }
    }

    val drtSystemInterface: DrtSystemInterface = module.provideDrtSystemInterface


    new ExportPortConfigController(Helpers.stubControllerComponents(), drtSystemInterface)
  }
}
