package drt.client.components

import diode.data.Ready
import drt.client.components.ArrivalGenerator.apiFlight
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, Percentage}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, LiveFeedSource, PaxTypeAndQueue, PaxTypes, Queues}
import utest.{TestSuite, _}


object BigSummaryBoxTests extends TestSuite {

  import BigSummaryBoxes._

  def tests = Tests {
    "Summary for the next 3 hours" - {
      "Given a rootModel with flightsWithSplits with flights arriving 2017-05-01T12:01Z onwards" - {
        "Given 0 flights" - {
          val rootModel = RootModel(portStatePot = Ready(PortState.empty))

          "AND a current time of 2017-05-01T12:00" - {
            val now = SDate(2016, 5, 1, 12)
            val nowPlus3Hours = now.addHours(3)

            "Then we can get a number of flights arriving in that period" - {
              val countOfFlights = rootModel.portStatePot.map(_.flights.values.count(f => {
                val flightDt = SDate(f.apiFlight.Scheduled)
                now.millisSinceEpoch <= flightDt.millisSinceEpoch && flightDt.millisSinceEpoch <= nowPlus3Hours.millisSinceEpoch
              }))
              assert(countOfFlights == Ready(0))
            }
          }
        }
        "Given 3 flights" - {

          val apiFlight1 = apiFlight(schDt = "2017-05-01T12:05Z", terminal = T1, actPax = Option(200))
          val apiFlight2 = apiFlight(schDt = "2017-05-01T13:05Z", terminal = T1, actPax = Option(300))
          val apiFlight3 = apiFlight(schDt = "2017-05-01T13:20Z", terminal = T1, actPax = Option(40))

          val rootModel = RootModel(portStatePot = Ready(PortState(
            List(
              ApiFlightWithSplits(apiFlight1, Set()),
              ApiFlightWithSplits(apiFlight2, Set()),
              ApiFlightWithSplits(apiFlight3, Set())),
            List(),
            List())))

          "AND a current time of 2017-05-01T12:00" - {
            val now = SDate("2017-05-01T12:00Z")
            val nowPlus3Hours = now.addHours(3)

            "Then we can get a number of flights arriving in that period" - {
              val countOfFlights = countFlightsInPeriod(rootModel, now, nowPlus3Hours)
              val expected = Ready(3)
              assert(countOfFlights == expected)
            }
            "And we can get the total pax to the PCP" - {
              val countOfPax = countPaxInPeriod(rootModel, now, nowPlus3Hours)
              assert(countOfPax == Ready(200 + 300 + 40))
            }
          }
        }
        "Given 3 flights with a nonZero PcpTime we use the pcpTime " - {
          val apiFlightPcpBeforeNow = apiFlight(schDt = "2017-05-01T11:40Z", terminal = T1, actPax = Option(7), pcpTime = Option(mkMillis("2017-05-01T11:40Z")))
          val apiFlight0aPcpAfterNow = apiFlight(schDt = "2017-05-01T11:40Z", terminal = T1, actPax = Option(11), pcpTime = Option(mkMillis("2017-05-01T12:05Z")))
          val apiFlight1 = apiFlight(schDt = "2017-05-01T12:05Z", terminal = T1, actPax = Option(200), pcpTime = Option(mkMillis("2017-05-01T12:05Z")))
          val apiFlight2 = apiFlight(schDt = "2017-05-01T13:05Z", terminal = T1, actPax = Option(300), pcpTime = Option(mkMillis("2017-05-01T13:15Z")))
          val apiFlight3 = apiFlight(schDt = "2017-05-01T13:20Z", terminal = T1, actPax = Option(40), pcpTime = Option(mkMillis("2017-05-01T13:22Z")))


          val rootModel = RootModel(portStatePot = Ready(PortState(
            List(
              ApiFlightWithSplits(apiFlightPcpBeforeNow, Set()),
              ApiFlightWithSplits(apiFlight0aPcpAfterNow, Set()),
              ApiFlightWithSplits(apiFlight1, Set()),
              ApiFlightWithSplits(apiFlight2, Set()),
              ApiFlightWithSplits(apiFlight3, Set())),
            List(),
            List())))

          "AND a current time of 2017-05-01T12:00" - {
            val now = SDate("2017-05-01T12:00Z")
            val nowPlus3Hours = now.addHours(3)

            "Then we can get a number of flights arriving in that period" - {
              val countOfFlights = countFlightsInPeriod(rootModel, now, nowPlus3Hours)
              val expected = Ready(4)
              assert(countOfFlights == expected)
            }
            "And we can get the total pax to the PCP" - {
              val countOfPax = countPaxInPeriod(rootModel, now, nowPlus3Hours)
              assert(countOfPax == Ready(11 + 200 + 300 + 40))
            }
          }
        }

        val terminal1 = T1

        "Given 3 flights with a nonZero PcpTime we use the pcpTime " - {
          "AND we can filter by Terminal - we're interested in T1" - {
            val ourTerminal = terminal1

            val apiFlight1 = apiFlight(schDt = "2017-05-01T12:05Z", terminal = terminal1, actPax = Option(200), pcpTime = Option(mkMillis("2017-05-01T12:05Z")))
            val apiFlight2 = apiFlight(schDt = "2017-05-01T13:05Z", terminal = terminal1, actPax = Option(300), pcpTime = Option(mkMillis("2017-05-01T13:15Z")))
            val notOurTerminal = apiFlight(schDt = "2017-05-01T13:20Z", terminal = Terminal("T4"), actPax = Option(40), pcpTime = Option(mkMillis("2017-05-01T13:22Z")))

            val flights = List(
              ApiFlightWithSplits(apiFlight1, Set()),
              ApiFlightWithSplits(apiFlight2, Set()),
              ApiFlightWithSplits(notOurTerminal, Set()))

            "AND a current time of 2017-05-01T12:00" - {
              val now = SDate("2017-05-01T12:00Z")
              val nowPlus3Hours = now.addHours(3)

              "Then we can get a number of flights arriving in that period" - {
                val flightsPcp = flightsInPeriod(flights, now, nowPlus3Hours)
                val flightsInTerminal = flightsAtTerminal(flightsPcp, ourTerminal)
                val countOfFlights = flightsInTerminal.length
                val expected = 2
                assert(countOfFlights == expected)
              }
            }
          }
        }
        "Given 3 flights " - {
          "And they have splits" - {
            "Then we can aggregate the splits for a graph" - {
              val apiFlight1 = apiFlight(schDt = "2017-05-01T12:05Z", terminal = terminal1, actPax = Option(200), pcpTime = Option(mkMillis("2017-05-01T12:05Z")))
              val apiFlight2 = apiFlight(schDt = "2017-05-01T13:05Z", terminal = terminal1, actPax = Option(300), pcpTime = Option(mkMillis("2017-05-01T13:15Z")))

              val splits1 = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 41, None, None),
                ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 23, None, None)),
                SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC), PaxNumbers)

              val splits2 = Splits(Set(
                ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None, None),
                ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 17, None, None))
                , SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC), PaxNumbers)

              val flights = FlightsWithSplitsDiff(
                List(ApiFlightWithSplits(apiFlight1, Set(splits1)),
                  ApiFlightWithSplits(apiFlight2, Set(splits2))), List())

              val aggSplits = aggregateSplits(flights.flightsToUpdate)

              val expectedAggSplits = Map(
                PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk) -> (41 + 11),
                PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> (23 + 17))

              assert(aggSplits == expectedAggSplits)
            }
          }
        }
        "Given 2 flights " - {
          "And they have percentage splits" - {
            "Then we can aggregate the splits by multiply the % against the bestPax so that we can show them in a graph" - {
              val flights = List(
                ApiFlightWithSplits(apiFlight(schDt = "2017-05-01T12:05Z", terminal = terminal1, actPax = Option(100), pcpTime = Option(mkMillis("2017-05-01T12:05Z")),
                  totalPax = Set(TotalPaxSource(100, LiveFeedSource, None))),
                  Set(Splits(Set(
                    ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 30, None, None),
                    ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 70, None, None)),
                    SplitSources.Historical, None, Percentage))),
                ApiFlightWithSplits(apiFlight(schDt = "2017-05-01T13:05Z", terminal = terminal1, actPax = Option(100), pcpTime = Option(mkMillis("2017-05-01T13:15Z")),
                  totalPax = Set(TotalPaxSource(100, LiveFeedSource, None))),
                  Set(Splits(Set(
                    ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 40, None, None),
                    ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 60, None, None)),
                    SplitSources.Historical, None, Percentage))))

              val aggSplits = aggregateSplits(flights)

              val expectedAggSplits = Map(
                PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk) -> (30 + 40),
                PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> (70 + 60))

              assert(aggSplits == expectedAggSplits)
            }
          }
        }
        "Given 2 flights " - {
          "And we're using the LHR tranPax aware bestpax" - {
            "And they have API PaxNo splits including a transfer split" - {
              "And they have tranpax" - {
                "Then we can aggregate the splits by adding them up - we also filter out the transfer split" - {
                  val flights = List(
                    ApiFlightWithSplits(apiFlight(schDt = "2017-05-01T12:05Z", terminal = terminal1, actPax = Option(300), tranPax = Option(100), pcpTime = Option(mkMillis("2017-05-01T12:05Z"))),
                      Set(Splits(Set(
                        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 60, None, None),
                        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 120, None, None),
                        ApiPaxTypeAndQueueCount(PaxTypes.Transit, Queues.Transfer, 20, None, None)),
                        SplitSources.Historical, None, PaxNumbers))))

                  val aggSplits = aggregateSplits(flights)

                  val expectedAggSplits = Map(
                    PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk) -> 60,
                    PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 120)

                  assert(aggSplits == expectedAggSplits)
                }
              }
            }
          }
        }
      }
    }
  }

  def mkMillis(t: String): MillisSinceEpoch = SDate(t).millisSinceEpoch
}
