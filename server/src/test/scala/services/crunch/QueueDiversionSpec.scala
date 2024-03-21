package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import services.crunch.TestDefaults.airportConfigForSplits
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Future

class QueueDiversionSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")

  "Concerning queue diversions" >> {
    val deskRatios = Map(EeaDesk -> 0.75, EGate -> 0.25)
    val splits = Map(PaxTypeAndQueue(EeaMachineReadable, EeaDesk) -> deskRatios(EeaDesk), PaxTypeAndQueue(EeaMachineReadable, EGate) -> deskRatios(EGate))

    val config = airportConfigForSplits(splits)
    val allGatesClosed = PortEgateBanksUpdates(Map(T1 -> EgateBanksUpdates(List(EgateBanksUpdate(0L, IndexedSeq(EgateBank(IndexedSeq(false, false, false, false, false))))))))

    "Given an arrival" >> {
      val pax = 100
      val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), totalPax = Option(pax))

      "When all queues are open I should see pax headed to all queues in the default splits" >> {
        implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
          airportConfig = config,
          now = () => dateNow))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveArrival)))
        expectPaxByQueue(Map(EeaDesk -> 75, EGate -> 25))
        crunch.shutdown()

        success
      }

      "When the egates are closed I should see all pax in the Eea queue" >> {
        implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
          airportConfig = config,
          now = () => dateNow,
          maybeEgatesProvider = Option(() => Future.successful(allGatesClosed))
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveArrival)))
        expectPaxByQueue(Map(EeaDesk -> pax))
        crunch.shutdown()

        success
      }

      "When the Egates and Eea desks are closed I should see all pax in the Non-Eea queue" >> {
        implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
          airportConfig = config.copy(minMaxDesksByTerminalQueue24Hrs = config.minMaxDesksByTerminalQueue24Hrs.updated(T1, Map(
            EeaDesk -> ((List.fill(24)(0), List.fill(24)(0))),
            NonEeaDesk -> ((List.fill(24)(0), List.fill(24)(5))),
            EGate -> ((List.fill(24)(0), List.fill(24)(0))),
          ))),
          now = () => dateNow,
          maybeEgatesProvider = Option(() => Future.successful(allGatesClosed))
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveArrival)))
        expectPaxByQueue(Map(NonEeaDesk -> 100))
        crunch.shutdown()

        success
      }
    }
  }
}
