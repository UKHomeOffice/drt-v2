package services

import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes.{PaxTypeAndQueueCount, PaxTypes, Queues, VoyagePaxSplits}
import services.workloadcalculator._
import services.workloadcalculator.PaxLoad.PaxType
import spatutorial.shared.ApiFlight
import utest.{TestSuite, _}

import scala.collection.immutable.Iterable

object WorkloadCalculatorTests extends TestSuite {
  def createApiFlight(iataFlightCode: String, airportCode: String, totalPax: Int, scheduledDatetime: String): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 1,
      AirportID = airportCode,
      Terminal = "",
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      SchDT = scheduledDatetime
    )

  def tests = TestSuite {
    'WorkloadCalculator - {

      "Given an ApiFlight and a multi-split definition, we should get back corresponding splits of the APIFlight's passengers" - {
        val flight = createApiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
        val splitRatios = List(
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
        )

        val expectedPaxSplits = List(
          PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eeaDesk, 25),
          PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eGate, 25)
        )
        val actualPaxSplits = PaxLoadCalculator.flightPaxSplits(flight, splitRatios)

        assert(expectedPaxSplits == actualPaxSplits)
      }

      "Given an ApiFlight and single split ratio, we should get back a VoyagePaxSplits containing a single pax split" - {
        val flight = createApiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
        val splitRatios = List(SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 1.0))
        val voyagesPaxSplit = PaxLoadCalculator.voyagePaxSplitsFromApiFlight(flight, splitRatios)

        assert(voyagesPaxSplit ==
          VoyagePaxSplits("LHR", "BA0001", 50, DateTime.parse(flight.SchDT),
            List(PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eeaDesk, 50))
          )
        )
      }

      "Given a map of queues to pax load, we should get back a list of queue workloads" - {
        val paxload = Map(
          Queues.eeaDesk ->
            Seq(
              PaxLoad(DateTime.parse("2020-01-01T00:00:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eeaDesk)),
              PaxLoad(DateTime.parse("2020-01-01T00:01:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eeaDesk))
            ),
          Queues.eGate ->
            Seq(PaxLoad(DateTime.parse("2020-01-01T00:00:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eGate)))
        )

        val expected = List(
          QueueWorkloads(
            Queues.eeaDesk,
            Seq(WL(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), WL(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20)),
            Seq(Pax(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), Pax(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20))
          ),
          QueueWorkloads(
            Queues.eGate,
            Seq(WL(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20)),
            Seq(Pax(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20))
          )
        )

        val result = PaxLoadCalculator.paxloadsToQueueWorkloads(paxload)

        assert(result == expected)
      }

      "Given two lists of WL return an aggregation of them" - {
        val l1 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val l2 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val combined = PaxLoadCalculator.combineWorkloads(l1, l2)
        assert(combined == List(WL(1577836800, 80.0), WL(1577836860, 20.0)))
      }

      "Given a single flight when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
        def splitRatioProvider(flight: ApiFlight) = List(
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
        )
        val flights = List(
          createApiFlight("BA0001", "LHR", 100, "2020-01-01T00:00:00")
        )

        val queueWorkloads = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider)(flights)
        val workloads = queueWorkloads.map(qw => (qw.queueName, qw.workloadsByMinute.toList)).toList
        println(s"Real ${workloads}")
        val tuples: List[(String, List[WL])] = List(
          Queues.eeaDesk -> List(WL(1577836800, 10.0), WL(1577836860, 10.0), WL(1577836920, 10.0), WL(1577836980, 10.0), WL(1577837040, 10.0)),
          Queues.eGate -> List(WL(1577836800, 10.0), WL(1577836860, 10.0), WL(1577836920, 10.0), WL(1577836980, 10.0), WL(1577837040, 10.0)))
        println(s"expe ${tuples}")
        assert(workloads == tuples)
      }
    }
  }
}
