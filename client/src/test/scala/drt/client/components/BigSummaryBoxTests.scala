package drt.client.components

import diode.data.Ready
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, Percentage}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T4, Terminal}
import uk.gov.homeoffice.drt.ports._
import utest.{TestSuite, _}


object BigSummaryBoxTests extends TestSuite {

  import BigSummaryBoxes._

  val paxFeedSourceOrder = List(ApiFeedSource, LiveFeedSource)

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

          val apiFlight1 = ArrivalGenerator.arrival(schDt = "2017-05-01T12:05Z", terminal = T1, totalPax = Option(200), feedSource = LiveFeedSource)
          val apiFlight2 = ArrivalGenerator.arrival(schDt = "2017-05-01T13:05Z", terminal = T1, totalPax = Option(300), feedSource = LiveFeedSource)
          val apiFlight3 = ArrivalGenerator.arrival(schDt = "2017-05-01T13:20Z", terminal = T1, totalPax = Option(40), feedSource = LiveFeedSource)

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
              val countOfPax = countPaxInPeriod(rootModel, now, nowPlus3Hours, paxFeedSourceOrder)
              assert(countOfPax == Ready(200 + 300 + 40))
            }
          }
        }
        "Given 3 flights with a nonZero PcpTime we use the pcpTime " - {
          val apiFlightPcpBeforeNow = ArrivalGenerator.live(schDt = "2017-05-01T11:40Z", terminal = T1, totalPax = Option(7))
            .toArrival(LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T11:40Z")))
          val apiFlight0aPcpAfterNow = ArrivalGenerator.live(schDt = "2017-05-01T11:40Z", terminal = T1, totalPax = Option(11))
            .toArrival(LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T11:40Z")))
          val apiFlight1 = ArrivalGenerator.live(schDt = "2017-05-01T12:05Z", terminal = T1, totalPax = Option(200))
            .toArrival(LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T12:05Z")))
          val apiFlight2 = ArrivalGenerator.live(schDt = "2017-05-01T13:05Z", terminal = T1, totalPax = Option(300))
            .toArrival(LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T13:15Z")))
          val apiFlight3 = ArrivalGenerator.live(schDt = "2017-05-01T13:20Z", terminal = T1, totalPax = Option(40))
            .toArrival(LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T13:22Z")))


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
              val expected = Ready(3)
              assert(countOfFlights == expected)
            }
            "And we can get the total pax to the PCP" - {
              val countOfPax = countPaxInPeriod(rootModel, now, nowPlus3Hours, paxFeedSourceOrder)
              assert(countOfPax == Ready(200 + 300 + 40))
            }
          }
        }

        val terminal1 = T1

        "Given 3 flights with a nonZero PcpTime we use the pcpTime " - {
          "AND we can filter by Terminal - we're interested in T1" - {
            val apiFlight1 = ArrivalGenerator.arrival(schDt = "2017-05-01T12:05Z", terminal = T1, totalPax = Option(200), feedSource = LiveFeedSource)
            val apiFlight2 = ArrivalGenerator.arrival(schDt = "2017-05-01T13:05Z", terminal = T1, totalPax = Option(300), feedSource = LiveFeedSource)
            val notOurTerminal = ArrivalGenerator.arrival(schDt = "2017-05-01T13:20Z", terminal = T4, totalPax = Option(40), feedSource = LiveFeedSource)

            val flights = List(
              ApiFlightWithSplits(apiFlight1, Set()),
              ApiFlightWithSplits(apiFlight2, Set()),
              ApiFlightWithSplits(notOurTerminal, Set()))

            "AND a current time of 2017-05-01T12:00" - {
              val now = SDate("2017-05-01T12:00Z")
              val nowPlus3Hours = now.addHours(3)

              "Then we can get a number of flights arriving in that period" - {
                val flightsPcp = flightsInPeriod(flights, now, nowPlus3Hours)
                val flightsInTerminal = flightsAtTerminal(flightsPcp, T1)
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
              val apiFlight1 = ArrivalGenerator.arrival(schDt = "2017-05-01T12:05Z", terminal = terminal1,
                totalPax = Option(200), feedSource = LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T12:05Z")))
              val apiFlight2 = ArrivalGenerator.arrival(schDt = "2017-05-01T13:05Z", terminal = terminal1,
                totalPax = Option(300), feedSource = LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T13:15Z")))

              val splits1 = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 41, None, None),
                ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 23, None, None)),
                SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC), PaxNumbers)

              val splits2 = Splits(Set(
                ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11, None, None),
                ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 17, None, None))
                , SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC), PaxNumbers)

              val flights = FlightsWithSplitsDiff(
                List(
                  ApiFlightWithSplits(apiFlight1, Set(splits1)),
                  ApiFlightWithSplits(apiFlight2, Set(splits2))
                ))

              val aggSplits = aggregateSplits(flights.flightsToUpdate, paxFeedSourceOrder)

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
                ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-05-01T12:05Z", terminal = terminal1,
                  totalPax = Option(100), feedSource = LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T12:05Z"))),
                  Set(Splits(Set(
                    ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 30, None, None),
                    ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 70, None, None)),
                    SplitSources.Historical, None, Percentage))),
                ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-05-01T13:05Z", terminal = terminal1,
                  totalPax = Option(100), feedSource = LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T13:05Z"))),
                  Set(Splits(Set(
                    ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 40, None, None),
                    ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 60, None, None)),
                    SplitSources.Historical, None, Percentage))))

              val aggSplits = aggregateSplits(flights, paxFeedSourceOrder)

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
                    ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-05-01T12:05Z", terminal = terminal1,
                      totalPax = Option(300), transPax = Option(100), feedSource = LiveFeedSource).copy(PcpTime = Option(mkMillis("2017-05-01T12:05Z"))),
                      Set(Splits(Set(
                        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 60, None, None),
                        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 120, None, None),
                        ApiPaxTypeAndQueueCount(PaxTypes.Transit, Queues.Transfer, 20, None, None)),
                        SplitSources.Historical, None, PaxNumbers))))

                  val aggSplits = aggregateSplits(flights, paxFeedSourceOrder)

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
