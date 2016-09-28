package services

import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes._
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator._
import spatutorial.shared.FlightsApi.QueueName
import spatutorial.shared.{ApiFlight, FlightsApi, Pax, WL}
import utest.{TestSuite, _}
import scala.collection.immutable.Seq
import scala.language.implicitConversions

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

  def tests = TestSuite {
    'WorkloadCalculator - {
      implicit def tupleToPaxTypeAndQueueCounty(t: (PaxType, String)): PaxTypeAndQueue = PaxTypeAndQueue(t._1, t._2)

      "queueWorkloadCalculator" - {
        "with simple pax splits all at the same paxType" - {
          def splitRatioProvider(flight: ApiFlight) = List(
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
          )

          val sut = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider) _

          "Examining workloads specifically" - {

            "Given a single flight with one minute's worth of flow when we apply paxSplits and flow rate, then we should see flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 20, startTime))

              val workloads = extractWorkloads(sut(flights))
              val expected: Map[FlightCode, List[WL]] = Map(
                Queues.eGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)))
              assert(workloads == expected)
            }

            "Given a single flight with two minutes of flow and two desks when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val workloads = extractWorkloads(sut(flights))
              val expected: Map[FlightCode, List[WL]] = Map(
                Queues.eGate -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0)),
                Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0)))
              assert(workloads == expected)
            }

            "Given 2 flights when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T01:10:00Z")
              )

              val workloads = extractWorkloads(sut(flights))
              val expected: Map[FlightCode, List[WL]] = Map(
                Queues.eGate -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                  WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0)),
                Queues.eeaDesk -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 10.0),
                  WL(asMillis("2020-01-01T01:10:00Z"), 10.0), WL(asMillis("2020-01-01T01:11:00Z"), 10.0)))
              assert(workloads == expected)
            }

            "Given 2 flights where load overlaps when we apply paxSplits and flow rate, " +
              "then we should see flow applied to the flight, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 40, scheduledDatetime = "2020-01-01T00:00:00Z")
              )

              val workloads = extractWorkloads(sut(flights))
              val expected: Map[FlightCode, List[WL]] = Map(
                Queues.eGate -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)),
                Queues.eeaDesk -> List(
                  WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)))

              assert(workloads == expected)
            }
          }

          "Examining Queue Workloads map" - {
            "Given a single flight with one minute's worth of flow when we apply paxSplits and flow rate, " +
              "then we should see queues containing flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 20, startTime))

              val queueWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])] = sut(flights)
              val expected: Map[QueueName, (Seq[WL], Seq[Pax])] = Map(
                Queues.eGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0))
                  )),
                Queues.eeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0))
                  )))
              assert(queueWorkloads == expected)
            }

            "Given a single flight with 2 minute's worth of flow, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flight, and splits applied to that flow" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 30, startTime))

              val queueWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])] = sut(flights)
              val expected: Map[QueueName, (Seq[WL], Seq[Pax])] = Map(
                Queues.eGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 5.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T00:01:00Z"), 5.0))
                  )),
                Queues.eeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T00:01:00Z"), 5.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T00:01:00Z"), 5.0))
                  )))
              assert(queueWorkloads == expected)
            }

            "Given 2 flights each with one minute's worth of flow not overlapping, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flights, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 20, scheduledDatetime = "2020-01-01T01:10:00Z")
              )

              val queueWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])] = sut(flights)
              val expected: Map[QueueName, (Seq[WL], Seq[Pax])] = Map(
                Queues.eGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T01:10:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T01:10:00Z"), 10.0))
                  )),
                Queues.eeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 10.0), WL(asMillis("2020-01-01T01:10:00Z"), 10.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 10.0), Pax(asMillis("2020-01-01T01:10:00Z"), 10.0))
                  )))
              assert(queueWorkloads == expected)
            }

            "Given 2 flights each with one minute's worth of overlapping flow, when we apply paxSplits and flow rate," +
              "then we should see queues containing flow applied to the flights, and splits applied to that flow" - {
              val flights = List(
                apiFlight("BA0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z"),
                apiFlight("ZZ0001", totalPax = 20, scheduledDatetime = "2020-01-01T00:00:00Z")
              )

              val queueWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])] = sut(flights)
              val expected: Map[QueueName, (Seq[WL], Seq[Pax])] = Map(
                Queues.eGate -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0))
                  )),
                Queues.eeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0))
                  )))
              assert(queueWorkloads == expected)

            }
          }
        }

        "with paxSplits of differing paxType to the same queue" - {
          def splitRatioProvider(flight: ApiFlight) = List(
            SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
            SplitRatio((PaxTypes.visaNational, Queues.eeaDesk), 0.5)
          )

          val sut = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider) _

          "Examining workloads specifically" - {
            "Given a single flight, we see different passenger types aggregated into one workload number" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val workloads = extractWorkloads(sut(flights))
              val expected: Map[FlightCode, List[WL]] = Map(
                Queues.eeaDesk -> List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)))
              assert(workloads == expected)
            }
          }

          "Examining Queue Workloads" - {
            "Given a single flight, we see different passenger types aggregated into one workload number" - {
              val startTime: String = "2020-01-01T00:00:00Z"
              val flights = List(apiFlight("BA0001", "LHR", 40, startTime))

              val queueWorkloads = sut(flights)
              val expected: Map[QueueName, (List[WL], List[Pax])] = Map(
                Queues.eeaDesk -> ((
                  List(WL(asMillis("2020-01-01T00:00:00Z"), 20.0), WL(asMillis("2020-01-01T00:01:00Z"), 20.0)),
                  List(Pax(asMillis("2020-01-01T00:00:00Z"), 20.0), Pax(asMillis("2020-01-01T00:01:00Z"), 20.0)))))
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
