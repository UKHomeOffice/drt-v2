package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.QueueStatusProviders.{HourlyStatuses, QueuesAlwaysOpen}
import drt.shared.Queues.{Closed, EGate, EeaDesk, NonEeaDesk, Open, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{PaxTypeAndQueue, PortCode, Queues, SDateLike}
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.TestDefaults.airportConfigForSplits

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
    val egatesClosed: Map[Terminal, Map[Queue, IndexedSeq[Queues.QueueStatus]]] = Map(T1 -> Map(
      EGate -> IndexedSeq.fill(24)(Closed),
      EeaDesk -> IndexedSeq.fill(24)(Open),
      NonEeaDesk -> IndexedSeq.fill(24)(Open),
    ))

    "Given an arrival, I should see pax headed to all queues in the default splits" >> {
      implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
        airportConfig = config.copy(queueStatusProvider = QueuesAlwaysOpen),
        now = () => dateNow))

      val pax = 100
      val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), actPax = Option(pax))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
      expectPaxByQueue(Map(EeaDesk -> 75, EGate -> 25))

      success
    }

    "Given an arrival, and zero max egates, I should see pax headed only to the desks" >> {
      implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
        airportConfig = config.copy(queueStatusProvider = HourlyStatuses(egatesClosed)),
        now = () => dateNow))

      val pax = 100
      val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), actPax = Option(pax))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
      expectPaxByQueue(Map(EeaDesk -> 100, EGate -> 0))

      success
    }
  }
}
