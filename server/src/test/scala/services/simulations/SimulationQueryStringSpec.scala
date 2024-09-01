package services.simulations

import drt.shared._
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PaxTypesAndQueues, Queues}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.util.{Failure, Success}

class SimulationQueryStringSpec extends Specification {
  private val paxFeedSourceOrder: Seq[FeedSource] = Seq(LiveFeedSource)
  "When parsing a query string back into a simulations params object" >> {
    "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
      val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&eGateOpenHours=1,2"
      val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
      val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

      val expected = Success(SimulationParams(
        terminal = Terminal("T1"),
        date = LocalDate(2020, 2, 2),
        passengerWeighting = 1.0,
        processingTimes = Map(),
        minDesksByQueue = Map(),
        maxDesks = Map(),
        eGateBanksSizes = IndexedSeq.fill(5)(5),
        slaByQueue = Map(),
        crunchOffsetMinutes = 0,
        eGateOpenHours = Seq(1, 2),
        paxFeedSourceOrder = paxFeedSourceOrder,
      ))

      val result = SimulationParams.fromQueryStringParams(qsValues, paxFeedSourceOrder)

      expected === result
    }
  }

  "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30&" +
      "EGate_min=1&NonEeaDesk_min=1&" +
      "EGate_max=3&NonEeaDesk_max=3&" +
      "EGate_sla=10&EeaDesk_sla=15"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60, PaxTypesAndQueues.eeaMachineReadableToEGate -> 30),
      minDesksByQueue = Map(Queues.EGate -> 1, Queues.NonEeaDesk -> 1),
      maxDesks = Map(Queues.EGate -> 3, Queues.NonEeaDesk -> 3),
      eGateBanksSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(1, 2),
      paxFeedSourceOrder = paxFeedSourceOrder,
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues, paxFeedSourceOrder)

    expected === result
  }

  "Given a query string map containing all invalid types then they should be ignored if they are not required" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      minDesksByQueue = Map(Queues.EGate -> 1),
      maxDesks = Map(Queues.EGate -> 3),
      eGateBanksSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(1, 2),
      paxFeedSourceOrder = paxFeedSourceOrder,
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues, paxFeedSourceOrder)

    expected === result
  }

  "Given a query string containing no open egate hours I should get back a valid SimulationParams with an empty Seq for eGate open hours" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      minDesksByQueue = Map(Queues.EGate -> 1),
      maxDesks = Map(Queues.EGate -> 3),
      eGateBanksSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(),
      paxFeedSourceOrder = paxFeedSourceOrder,
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues, paxFeedSourceOrder)

    expected === result
  }

  "Missing required fields then I should get back a failure" >> {
    val qs = "date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"
    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val result = SimulationParams.fromQueryStringParams(qsValues, paxFeedSourceOrder)

    result.isInstanceOf[Failure[SimulationParams]]
  }
}
