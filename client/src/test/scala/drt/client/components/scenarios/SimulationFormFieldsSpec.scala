package drt.client.components.scenarios

import drt.shared.SimulationParams
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ PaxTypesAndQueues, Queues }
import uk.gov.homeoffice.drt.time.LocalDate
import utest.{ TestSuite, _ }

import scala.util.Success

class SimulationFormFieldsSpec extends TestSuite {
  private def queryParams(query: String): Map[String, Seq[String]] =
    query.split("&").map(_.split("=", 2)).map(parts => parts(0) -> Seq(parts(1))).toMap

  val tests: Tests = Tests {
    test("serializing a minimal simulation") {
      val fields = SimulationFormFields(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        Option(1.0),
        Map(),
        Map(),
        0,
        IndexedSeq.fill(5)(Option(5)),
        Map(),
        0,
        Seq(1)
      )
      val query = fields.toQueryStringParams
      val params = queryParams(query)

      test("includes the expected parameters without duplicates") {
        assert(query.split("&").length == params.size)
        assert(params("passengerWeighting").head.toDouble == 1.0)
        assert((params - "passengerWeighting") == Map(
          "terminal" -> Seq("T1"),
          "date" -> Seq("2020-02-02"),
          "crunchOffsetMinutes" -> Seq("0"),
          "eGateOpenHours" -> Seq("1"),
          "eGateBankSizes" -> Seq("5,5,5,5,5"),
          "terminalDesks" -> Seq("0")
        ))
      }

      test("can be parsed by the simulation endpoint") {
        val expected = SimulationParams(
          terminal = Terminal("T1"),
          date = LocalDate(2020, 2, 2),
          passengerWeighting = 1.0,
          processingTimes = Map(),
          minDesksByQueue = Map(),
          maxDesks = 0,
          eGateBankSizes = IndexedSeq(5, 5, 5, 5, 5),
          slaByQueue = Map(),
          crunchOffsetMinutes = 0,
          eGateOpenHours = Seq(1)
        )
        assert(SimulationParams.fromQueryStringParams(params) == Success(expected))
      }

      test("serializes closed eGates without bank sizes") {
        val closedFields = fields.copy(eGateBankSizes = IndexedSeq.empty, eGateOpenHours = Seq.empty)
        val closedParams = queryParams(closedFields.toQueryStringParams)
        assert(!closedParams.contains("eGateBankSizes"))
        assert(closedParams("eGateOpenHours") == Seq(""))
        val parsed = SimulationParams.fromQueryStringParams(closedParams)
        assert(parsed.map(p => (p.eGateBankSizes, p.eGateOpenHours)) ==
          Success((IndexedSeq.empty[Int], Seq.empty[Int])))
      }
    }

    test("serializing a simulation with queue data") {
      val fields = SimulationFormFields(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        Option(1.25),
        Map(
          PaxTypesAndQueues.eeaMachineReadableToDesk -> Option(60),
          PaxTypesAndQueues.eeaMachineReadableToEGate -> Option(30)
        ),
        Map(Queues.EGate -> Option(1), Queues.NonEeaDesk -> Option(1)),
        6,
        IndexedSeq.fill(5)(Option(5)),
        Map(Queues.EGate -> Option(10), Queues.EeaDesk -> Option(15)),
        15,
        Seq(1, 2)
      )
      val query = fields.toQueryStringParams
      val params = queryParams(query)

      test("includes the expected parameters without duplicates") {
        assert(query.split("&").length == params.size)
        assert(params("passengerWeighting").head.toDouble == 1.25)
        assert((params - "passengerWeighting") == Map(
          "terminal" -> Seq("T1"),
          "date" -> Seq("2020-02-02"),
          "crunchOffsetMinutes" -> Seq("15"),
          "eGateOpenHours" -> Seq("1,2"),
          "eGateBankSizes" -> Seq("5,5,5,5,5"),
          "EeaMachineReadable_EeaDesk" -> Seq("60"),
          "EeaMachineReadable_EGate" -> Seq("30"),
          "EGate_min" -> Seq("1"),
          "NonEeaDesk_min" -> Seq("1"),
          "terminalDesks" -> Seq("6"),
          "EGate_sla" -> Seq("10"),
          "EeaDesk_sla" -> Seq("15")
        ))
      }

      test("can be parsed by the simulation endpoint") {
        val expected = SimulationParams(
          terminal = Terminal("T1"),
          date = LocalDate(2020, 2, 2),
          passengerWeighting = 1.25,
          processingTimes = Map(
            PaxTypesAndQueues.eeaMachineReadableToDesk -> 60,
            PaxTypesAndQueues.eeaMachineReadableToEGate -> 30
          ),
          minDesksByQueue = Map(Queues.EGate -> 1, Queues.NonEeaDesk -> 1),
          maxDesks = 6,
          eGateBankSizes = IndexedSeq(5, 5, 5, 5, 5),
          slaByQueue = Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
          crunchOffsetMinutes = 15,
          eGateOpenHours = Seq(1, 2)
        )
        assert(SimulationParams.fromQueryStringParams(params) == Success(expected))
      }
    }
  }
}
