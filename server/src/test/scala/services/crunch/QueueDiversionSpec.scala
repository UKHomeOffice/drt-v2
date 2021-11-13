package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.TestDefaults.airportConfigForSplits
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
//import uk.gov.homeoffice.drt.ports.QueueStatusProviders.QueuesAlwaysOpen
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PortCode}

import scala.collection.immutable.List

class QueueDiversionSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")

  "Concerning queue diversions" >> {
    val deskRatios = Map(EeaDesk -> 0.75, EGate -> 0.25)
    val splits = Map(PaxTypeAndQueue(EeaMachineReadable, EeaDesk) -> deskRatios(EeaDesk), PaxTypeAndQueue(EeaMachineReadable, EGate) -> deskRatios(EGate))

    val config = airportConfigForSplits(splits)

    "Given an arrival, I should see pax headed to all queues in the default splits" >> {
      implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
        airportConfig = config,//.copy(queueStatusProvider = QueuesAlwaysOpen),
        now = () => dateNow))

      val pax = 100
      val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), actPax = Option(pax))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
      expectPaxByQueue(Map(EeaDesk -> 75, EGate -> 25))

      success
    }
  }
}
