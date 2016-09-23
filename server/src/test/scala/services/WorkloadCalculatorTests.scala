package services

import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes.PaxTypes._
import services.workloadcalculator.PassengerQueueTypes._
import services.workloadcalculator._
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import spatutorial.shared.{ApiFlight, Pax, QueueWorkloads, WL}
import utest.{TestSuite, _}

import scala.collection.immutable.{IndexedSeq, Iterable, NumericRange, Seq}

object WorkloadCalculatorTests extends TestSuite {
  def apiFlight(iataFlightCode: String, airportCode: String, totalPax: Int, scheduledDatetime: String): ApiFlight =
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
      implicit def tupleToPaxTypeAndQueueCounty(t: (PaxType, String)): PaxTypeAndQueue = PaxTypeAndQueue(t._1, t._2)
      "Given an ApiFlight and a multi-split definition, we should get back corresponding splits of the APIFlight's passengers" - {
        val flight = apiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
        val splitRatios = List(
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
        )

        val expectedPaxSplits = List(
          PaxTypeAndQueueCount((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 25),
          PaxTypeAndQueueCount((PaxTypes.eeaMachineReadable, Queues.eGate), 25)
        )
        val actualPaxSplits = PaxLoadCalculator.flightPaxSplits(flight, splitRatios)

        assert(expectedPaxSplits == actualPaxSplits)
      }


      "Given a map of queues to pax load, we should get back a list of queue workloads" - {
        val paxload = Map(
          Queues.eeaDesk ->
            Seq(
              PaxLoadAt(DateTime.parse("2020-01-01T00:00:00"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20)),
              PaxLoadAt(DateTime.parse("2020-01-01T00:01:00"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20))),
          Queues.eGate ->
            Seq(PaxLoadAt(DateTime.parse("2020-01-01T00:00:00"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 20))))

        val expected = List(
          QueueWorkloads(
            Queues.eeaDesk,
            Seq(WL(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), WL(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20)),
            Seq(Pax(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), Pax(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20))),
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
          apiFlight("BA0001", "LHR", 100, "2020-01-01T00:00:00")
        )

        val queueWorkloads = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider)(flights)
        val workloads = queueWorkloads.map(qw => (qw.queueName, qw.workloadsByMinute.toList)).toList
        val tuples: List[(String, List[WL])] = List(
          Queues.eGate -> List(WL(1577836800, 10.0), WL(1577836860, 10.0), WL(1577836920, 10.0), WL(1577836980, 10.0), WL(1577837040, 10.0)),
          Queues.eeaDesk -> List(WL(1577836800, 10.0), WL(1577836860, 10.0), WL(1577836920, 10.0), WL(1577836980, 10.0), WL(1577837040, 10.0)))
        assert(workloads == tuples)
      }

      "Given a flight, I should get back a voyage pax split representing that flight" - {

        val flight = apiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")

        val expected = VoyagePaxSplits(
          "LHR",
          "BA0001",
          50,
          DateTime.parse("2020-01-01T00:00:00"),
          Seq(
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 2.5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 2.5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 5)
          )
        )

        def splitRatioProvider(flight: ApiFlight) = List(
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.25),
          SplitRatio((PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.25),
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
        )

        val result = PaxLoadCalculator.voyagePaxSplitsFromApiFlight(splitRatioProvider)(flight)
        assert(result == expected)
      }

      "Given voyage pax splits, then we should get pax loads per type of passenger for each desk at a time" - {
        val expected = Map(Queues.eeaDesk -> Seq(
          PaxLoadAt(
            DateTime.parse("2020-01-01T00:00:00"),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20)
          )
        ))

        val voyagePaxSplits = VoyagePaxSplits(
          "LHR",
          "BA123",
          2,
          DateTime.parse("2020-01-01T00:00:00"),
          Seq(PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20))
        )

        val result = PaxLoadCalculator.voyagePaxLoadByDesk(voyagePaxSplits)

        assert(result == expected)
      }

      "Given voyage pax splits with multiple paxloads, then we should get pax loads per type of passenger for each desk at a time" - {
        val voyagePaxSplits = VoyagePaxSplits(
          "LHR",
          "BA0001",
          50,
          DateTime.parse("2020-01-01T00:00:00"),
          Seq(
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 2.5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 2.5),
            PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 5)
          )
        )

        val expected = Map(
          PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk) -> Seq(
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:00:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:01:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:02:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 2.5)
            )
          ),
          PaxTypeAndQueue(eeaMachineReadable, Queues.eGate) -> Seq(
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:00:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:01:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:02:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 5.0)
            )
          ),
          PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk) -> Seq(
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:00:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:01:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5.0)
            ),
            PaxLoadAt(
              DateTime.parse("2020-01-01T00:02:00"),
              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 2.5)
            )
          )
        )
        //        Map(PaxTypeAndQueue(eeaNonMachineReadable,eeaDesk) ->
        //        Vector(
        // PaxLoadAt(2020-01-01T00:00:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaNonMachineReadable,eeaDesk),5.0)),
        // PaxLoadAt(2020-01-01T00:01:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaNonMachineReadable,eeaDesk),5.0)),
        // PaxLoadAt(2020-01-01T00:02:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaNonMachineReadable,eeaDesk),2.5))),
        // PaxTypeAndQueue(eeaMachineReadable,eGate) ->
        // Vector(PaxLoadAt(2020-01-01T00:00:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eGate),10.0)),
        // PaxLoadAt(2020-01-01T00:01:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eGate),10.0)),
        // PaxLoadAt(2020-01-01T00:02:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eGate),5.0))),
        // PaxTypeAndQueue(eeaMachineReadable,eeaDesk) ->
        // Vector(PaxLoadAt(2020-01-01T00:00:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eeaDesk),5.0)),
        // PaxLoadAt(2020-01-01T00:01:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eeaDesk),5.0)),
        // PaxLoadAt(2020-01-01T00:02:00.000Z,PaxTypeAndQueueCount(PaxTypeAndQueue(eeaMachineReadable,eeaDesk),2.5))))

        val result = PaxLoadCalculator.voyagePaxLoadByDesk(voyagePaxSplits)

        assert(result == expected)
      }
    }
  }
}
