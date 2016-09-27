package services

import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes.PaxTypes._
import services.workloadcalculator.PassengerQueueTypes._
import services.workloadcalculator._
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import spatutorial.shared.{ApiFlight, Pax, WL}
import utest.{TestSuite, _}

import scala.collection.immutable.{IndexedSeq, Iterable, NumericRange, Seq}

object WorkloadCalculatorTests extends TestSuite {
  def apiFlight(iataFlightCode: String, airportCode: String = "EDI", totalPax: Int, scheduledDatetime: String): ApiFlight =
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

//  voyagePaxSplit
//  List(
//    (
//      1577836800000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eeaDesk),10.0)), (
//      1577836800000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eGate),10.0)), (
//      1577836860000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eeaDesk),10.0)), (
//      1577836860000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eGate),10.0)))
//
//
//  paxLoadsByDeskAndMinute Map(
//    (
//      eGate,1577836860000) -> List(
//      (
//        1577836860000,PaxTypeAndQueueCount(
//        PaxTypeAndQueue(
//          eeaMachineReadable,eGate),10.0))), (
//    eeaDesk,1577836860000) -> List(
//    (
//      1577836860000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eeaDesk),10.0))), (
//    eeaDesk,1577836800000) -> List(
//    (
//      1577836800000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eeaDesk),10.0))), (
//    eGate,1577836800000) -> List(
//    (
//      1577836800000,PaxTypeAndQueueCount(
//      PaxTypeAndQueue(
//        eeaMachineReadable,eGate),10.0))))


  def tests = TestSuite {
    'WorkloadCalculator - {
      implicit def tupleToPaxTypeAndQueueCounty(t: (PaxType, String)): PaxTypeAndQueue = PaxTypeAndQueue(t._1, t._2)

      "queueWorkloadCalculator" - {
        "with simple pax splits all at the same paxType" - {
          def splitRatioProvider(flight: ApiFlight) = List(
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
          )

          def defaultProcessingTimes(paxType: PaxType) = 1.0
          def paxLoadMultiplier(paxType: PaxType): Double = paxType match {
            case PaxTypes.visaNational => 0.6
            case PaxTypes.eeaMachineReadable => 1.0

          }
          val sut = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider) _

          "Given a single flight with one minute's worth of flow when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
            val startTime: String = "2020-01-01T00:00:00Z"
            val flights = List(
              apiFlight("BA0001", "LHR", 20, startTime)
            )

            val queueWorkloads = sut(flights)
            val workloads = queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
            val expected: Map[FlightCode, List[WL]] = Map(
              Queues.eGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
              Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)))
            assert(workloads == expected)
          }
          "Given a single flight with two minutes of flow and two desks when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
            val startTime: String = "2020-01-01T00:00:00Z"
            val flights = List(
              apiFlight("BA0001", "LHR", 40, startTime)
            )

            val queueWorkloads = sut(flights)
            val workloads = queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
            val expected: Map[FlightCode, List[WL]] = Map(
              Queues.eGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0)),
              Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0)))
            assert(workloads == expected)
          }
          "Given two flights when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
            val flights = List(
              apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
              apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T01:10:00Z")
            )

            val queueWorkloads = sut(flights)
            val workloads = queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
            val expected: Map[FlightCode, List[WL]] = Map(
              Queues.eGate -> List(
                WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0)),
              Queues.eeaDesk -> List(
                WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0)))
            assert(workloads == expected)
          }

          "Given two flights where load overlaps when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
            val flights = List(
              apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
              apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z")
            )

            val queueWorkloads = sut(flights)
            val workloads = queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
            val expected: Map[FlightCode, List[WL]] = Map(
              Queues.eGate -> List(
                WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)),
              Queues.eeaDesk -> List(
                WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)))

            assert(workloads == expected)
          }
        }

        "with paxSplits of differing paxType to the same queue" - {
          def splitRatioProvider(flight: ApiFlight) = List(
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
            SplitRatio((PaxTypes.visaNational, Queues.eeaDesk), 0.5)
          )

          val sut = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider) _

          "Given a single flight, we see different passenger types aggregated into one workload number" - {
            val startTime: String = "2020-01-01T00:00:00Z"
            val flights = List(
              apiFlight("BA0001", "LHR", 40, startTime)
            )

            val queueWorkloads = sut(flights)

            val workloads = queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
            val expected: Map[FlightCode, List[WL]] = Map(
              Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)))
            assert(workloads == expected)
          }

        }
      }

      "Given a map of queues to pax load by minute, we should get back a list of queue workloads" - {
        val paxload = Map(
          Queues.eeaDesk ->
            Seq(
              PaxLoadAt(DateTime.parse("2020-01-01T00:00:00Z"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20)),
              PaxLoadAt(DateTime.parse("2020-01-01T00:01:00Z"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20))),
          Queues.eGate ->
            Seq(PaxLoadAt(DateTime.parse("2020-01-01T00:00:00Z"), PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 20))))

        val result = PaxLoadCalculator.paxloadsToQueueWorkloads(paxload)

        val expected = Map(
          Queues.eeaDesk -> (
            Seq(WL(asMillis("2020-01-01T00:00:00Z"), 20), WL(asMillis("2020-01-01T00:01:00Z"), 20)),
            Seq(Pax(asMillis("2020-01-01T00:00:00Z"), 20), Pax(asMillis("2020-01-01T00:01:00Z"), 20))),
          Queues.eGate -> (
            Seq(WL(asMillis("2020-01-01T00:00:00Z"), 20)),
            Seq(Pax(asMillis("2020-01-01T00:00:00Z"), 20))))

        assert(result == expected)
      }

      "Given two lists of WL return an aggregation of them" - {
        val l1 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val l2 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val combined = PaxLoadCalculator.combineWorkloads(l1, l2)
        assert(combined == List(WL(1577836800, 80.0), WL(1577836860, 20.0)))
      }


//
//      "Given a flight, I should get back a voyage pax split representing that flight" - {
//
//        val flight = apiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
//
//        val expected = VoyagePaxSplits("LHR", "BA0001", DateTime.parse("2020-01-01T00:00:00"), List(
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 10),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 2.5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 2.5),
//          PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 5)
//        ))
//
//        def splitRatioProvider(flight: ApiFlight) = List(
//          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.25),
//          SplitRatio((PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.25),
//          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
//        )
//
//        val result = PaxLoadCalculator.voyagePaxSplitsFromApiFlight(splitRatioProvider)(flight)
//        assert(result == expected)
//      }

//      "paxTypeAndQueueToPaxLoadAtTime" - {
//        "Given voyage pax splits, then we should get pax loads per type of passenger for each desk at a time" - {
//          val result = PaxLoadCalculator.paxTypeAndQueueToPaxLoadAtTime(
//            DateTime.parse("2020-01-01T00:00:00Z"),
//            Seq(PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20))
//          )
//
//          val expected = scala.collection.immutable.Map(Queues.eeaDesk -> IndexedSeq(
//            PaxLoadAt(
//              DateTime.parse("2020-01-01T00:00:00Z"),
//              PaxTypeAndQueueCount(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 20)
//            )
//          ))
//
//          assert(result == expected)
//        }
//      }
    }
  }

  def asMillis(dateTimeString: String): Long = {
    DateTime.parse(dateTimeString).getMillis
  }
}
