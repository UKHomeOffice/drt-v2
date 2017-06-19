package services

import drt.services.AirportConfigHelpers
import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes._
import services.workloadcalculator._
import drt.shared.FlightsApi.QueueName
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import utest.{TestSuite, _}

import scala.collection.immutable.Seq
import scala.language.implicitConversions

object WorkloadCalculatorTests extends TestSuite with AirportConfigHelpers {
  def apiFlight(flightCode: String,
                airportCode: String = "EDI",
                totalPax: Int,
                scheduledDatetime: String,
                terminal: String = "A1",
                origin: String = "",
                flightId: Int = 1,
                lastKnownPax: Option[Int] = None,
                pcpTime: Long = 0
               ): Arrival =
    Arrival(
      FlightID = flightId,
      SchDT = scheduledDatetime,
      Terminal = terminal,
      Origin = origin,
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 0,
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      AirportID = airportCode,
      rawICAO = flightCode,
      rawIATA = flightCode,
      PcpTime = if (pcpTime != 0) pcpTime else SDate(scheduledDatetime).millisSinceEpoch,
      LastKnownPax = lastKnownPax
    )

  def tests = TestSuite {
    'WorkloadCalculator - {
      implicit def tupleToPaxTypeAndQueueCounty(t: (PaxType, String)): PaxTypeAndQueue = PaxTypeAndQueue(t._1, t._2)

      "queueWorkloadCalculator" - {
        def defaultProcTimesProvider(paxTypeAndQueue: PaxTypeAndQueue) = 1

        "with simple pax splits all at the same paxType" - {
          def splitRatioProvider(flight: Arrival) = Some(SplitRatios(
            TestAirportConfig,
            List(
              SplitRatio((PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.5),
              SplitRatio((PaxTypes.EeaMachineReadable, Queues.EGate), 0.5)
            )))

          val calcPaxTypeAndQueueCountForAFlightOverTime = PaxLoadCalculator.voyagePaxSplitsFlowOverTime(splitRatioProvider, BestPax.bestPax) _

          val sut = PaxLoadCalculator.queueWorkAndPaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, defaultProcTimesProvider) _

          "Examining workloads specifically" - {

            "Given a single flight with one minute's worth of flow when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 20, startTime))

              val workloads = extractWorkloads(sut(flights)).toSet
              val expected = Map(
                Queues.EGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                Queues.EeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0))).toSet
              assert(workloads == expected)
            }

            "Given a single flight with two minutes of flow and two desks when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val workloads = extractWorkloads(sut(flights)).toSet
              val expected = Map(
                Queues.EGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0)),
                Queues.EeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0))).toSet
              assert(workloads == expected)
            }

            "Given 2 flights when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T01:10:00Z")
              )

              val workloads = extractWorkloads(sut(flights)).toSet
              val expected = Map(
                Queues.EGate -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                  WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0)),
                Queues.EeaDesk -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                  WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0))).toSet
              assert(workloads == expected)
            }

            "Given 2 flights where load overlaps when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z")
              )

              val workloads = extractWorkloads(sut(flights)).toSet
              val expected = Map(
                Queues.EGate -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)),
                Queues.EeaDesk -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0))).toSet

              assert(workloads == expected)
            }
          }

          "Examining Queue Workloads map" - {
            "Given a single flight with one minute's worth of flow when we apply paxSplits and flow rate, " +
              "then we should see queues containing flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 20, startTime))

              val queueWorkloads = sut(flights).toSet
              val expected = Map(
                Queues.EGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0))
                )),
                Queues.EeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0))
                ))).toSet
              assert(queueWorkloads == expected)
            }

            "Given a single flight with 2 minute's worth of flow, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 30, startTime))

              val queueWorkloads = sut(flights).toSet
              val expected = Map(
                Queues.EGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 5.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T00:01:00Z"), 5.0))
                )),
                Queues.EeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 5.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T00:01:00Z"), 5.0))
                ))).toSet
              assert(queueWorkloads == expected)
            }

            "Given 2 flights each with one minute's worth of flow not overlapping, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flights, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 20, scheduledDatetime = "2020-01-01T01:10:00Z")
              )

              val queueWorkloads = sut(flights).toSet
              val expected = Map(
                Queues.EGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T01:10:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T01:10:00Z"), 10.0))
                )),
                Queues.EeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T01:10:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T01:10:00Z"), 10.0))
                ))).toSet
              assert(queueWorkloads == expected)
            }

            "Given 2 flights each with one minute's worth of overlapping flow, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flights, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z")
              )

              val queueWorkloads = sut(flights).toSet
              val expected = Map(
                Queues.EGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0))
                )),
                Queues.EeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0))
                ))).toSet
              assert(queueWorkloads == expected)

            }
          }
        }

        "with paxSplits of differing paxType to the same queue" - {
          def splitRatioProvider(flight: Arrival) = Some(SplitRatios(
            TestAirportConfig,
            List(
              SplitRatio((PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.5),
              SplitRatio((PaxTypes.VisaNational, Queues.EeaDesk), 0.5)
            )))

          val calcPaxTypeAndQueueCountForAFlightOverTime = PaxLoadCalculator.voyagePaxSplitsFlowOverTime(splitRatioProvider, BestPax.bestPax) _

          val sut = PaxLoadCalculator.queueWorkAndPaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, defaultProcTimesProvider) _

          "Examining workloads specifically" - {
            "Given a single flight, we see different passenger types aggregated into one workload number" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val workloads = extractWorkloads(sut(flights)).toSet
              val expected = Map(
                Queues.EeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0))).toSet
              assert(workloads == expected)
            }
          }

          "Examining Queue Workloads" - {
            "Given a single flight, we see different passenger types aggregated into one workload number" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val queueWorkloads = sut(flights).toSet
              val expected = Map(
                Queues.EeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0), Pax(asMillis("2020-01-01T00:01:00Z"), 20.0))))).toSet
              assert(queueWorkloads == expected)
            }
          }
        }
      }
    }
  }

  def extractWorkloads(queueWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])]): Map[FlightsApi.QueueName, List[WL]] = {
    queueWorkloads.map(qw => (qw._1, qw._2._1.toList))
  }

  def asMillis(dateTimeString: String): Long = {
    DateTime.parse(dateTimeString).getMillis
  }
}
