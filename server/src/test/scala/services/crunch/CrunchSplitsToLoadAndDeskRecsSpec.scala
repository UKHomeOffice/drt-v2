package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate, eeaNonMachineReadableToDesk}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports.{PortCode, Queues}

import scala.collection.immutable.{List, Map, Seq, SortedMap}
import scala.concurrent.duration._


class CrunchSplitsToLoadAndDeskRecsSpec extends CrunchTestLike {
  isolated
  sequential

  "Crunch split workload flow " >> {
    "Given a flight with 21 passengers and splits to eea desk & egates " >> {
      "When I ask for queue loads " >> {
        "Then I should see 4 queue loads, 2 for the first 20 pax to each queue and 2 for the last 1 split to each queue" >> {

          val scheduled = "2017-01-01T00:00Z"
          val edSplit = 0.25
          val egSplit = 0.75

          val flights = Flights(List(
            ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
          ))

          val airportConfigWithEgates = defaultAirportConfig.copy(
            slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25),
            queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.EGate)),
            terminalPaxSplits = Map(T1 -> SplitRatios(
              SplitSources.TerminalAverage,
              SplitRatio(eeaMachineReadableToDesk, edSplit),
              SplitRatio(eeaMachineReadableToEGate, egSplit)
            )),
            terminalProcessingTimes = Map(T1 -> Map(
              eeaMachineReadableToDesk -> 20d / 60,
              eeaMachineReadableToEGate -> 35d / 60))
          )
          val crunch = runCrunchGraph(TestConfig(
            now = () => SDate(scheduled),
            airportConfig = airportConfigWithEgates))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

          val expected = Map(T1 -> Map(
            Queues.EeaDesk -> Seq(20 * edSplit, 1 * edSplit),
            Queues.EGate -> Seq(20 * egSplit, 1 * egSplit)
          ))

          crunch.portStateTestProbe.fishForMessage(5 seconds) {
            case ps: PortState =>
              paxLoadsFromPortState(ps, 2) == expected
          }

          success
        }
      }
    }

    "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" >> {
      "When I ask for queue loads " >> {
        "Then I should see two eea desk queue loads containing the 2 passengers and their proc time" >> {
          val scheduled = "2017-01-01T00:00Z"
          val scheduled2 = "2017-01-01T00:01Z"

          val flights = Flights(List(
            ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(1)),
            ArrivalGenerator.arrival(schDt = scheduled2, iata = "SA123", terminal = T1, actPax = Option(1))
          ))

          val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled)))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

          val expected = Map(
            T1 -> Map(
              Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0),
              Queues.NonEeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0)),
            T2 -> Map(
              Queues.EeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0),
              Queues.NonEeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0)))

          crunch.portStateTestProbe.fishForMessage(5 seconds) {
            case ps: PortState =>
              paxLoadsFromPortState(ps, 5) == expected
          }

          success
        }
      }
    }

    "Given 1 flight with 100 passengers eaa splits to desk and eGates" >> {
      "When I ask for queue loads " >> {
        "Then I should see the correct loads for each queue" >> {
          val scheduled = "2017-01-01T00:00Z"

          val flights = Flights(List(
            ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(100))
          ))

          val crunch = runCrunchGraph(TestConfig(
            now = () => SDate(scheduled),
            airportConfig = defaultAirportConfig.copy(
              slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
              queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate)),
              terminalProcessingTimes = Map(T1 -> Map(
                eeaMachineReadableToDesk -> 0.25,
                eeaMachineReadableToEGate -> 0.3,
                eeaNonMachineReadableToDesk -> 0.4
              )),
              terminalPaxSplits = Map(T1 -> SplitRatios(
                SplitSources.TerminalAverage,
                List(SplitRatio(eeaMachineReadableToDesk, 0.25),
                  SplitRatio(eeaMachineReadableToEGate, 0.25),
                  SplitRatio(eeaNonMachineReadableToDesk, 0.5)
                )
              ))
            )))
          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

          val expected = Map(T1 -> Map(
            Queues.EeaDesk -> List(5.25, 5.25, 5.25, 5.25, 5.25),
            Queues.EGate -> List(1.5, 1.5, 1.5, 1.5, 1.5),
            Queues.NonEeaDesk -> List(0, 0, 0, 0, 0)
          ))

          crunch.portStateTestProbe.fishForMessage(5 seconds) {
            case ps: PortState =>
              workLoadsFromPortState(ps, 5) == expected
          }

          success
        }
      }
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk " >> {
        "When request a crunch " >> {
          "Then I should see a pax load of 5 (20 * 0.25)" >> {
            val scheduled = "2017-01-01T00:00Z"

            val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(20))
            val flights = Flights(List(flight))

            val crunch = runCrunchGraph(TestConfig(
              now = () => SDate(scheduled),
              airportConfig = defaultAirportConfig.copy(
                slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
                terminalProcessingTimes = Map(T1 -> Map(
                  eeaMachineReadableToDesk -> 20d / 60,
                  eeaMachineReadableToEGate -> 35d / 60)),
                terminalPaxSplits = Map(T1 -> SplitRatios(
                  SplitSources.TerminalAverage,
                  SplitRatio(eeaMachineReadableToDesk, 0.25)
                ))
              )
            ))

            offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

            val expected = Map(
              T1 -> Map(
                Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0),
                Queues.NonEeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0)),
              T2 -> Map(
                Queues.EeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0),
                Queues.NonEeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0)
              ))

            crunch.portStateTestProbe.fishForMessage(5 seconds) {
              case ps: PortState =>
                paxLoadsFromPortState(ps, 5) == expected
            }

            success
          }
        }
      }
    }

    "Split source precedence " >> {
      "Given a flight with both api & csv splits " >> {
        "When I crunch " >> {
          "I should see pax loads calculated from the api splits and applied to the arrival's pax" >> {

            val scheduled = "2017-01-01T00:00Z"

            val arrival = ArrivalGenerator.arrival(origin = PortCode("JFK"), schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(10), airportId = PortCode("LHR"))

            val crunch = runCrunchGraph(TestConfig(
              now = () => SDate(scheduled),
              airportConfig = defaultAirportConfig.copy(
                slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25),
                queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.EGate)),
                terminalPaxTypeQueueAllocation = defaultAirportConfig.terminalPaxTypeQueueAllocation.updated(
                  T1, defaultAirportConfig.terminalPaxTypeQueueAllocation(T1).updated(EeaMachineReadable, List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
                  )
                ),
                terminalProcessingTimes = Map(T1 -> Map(
                  eeaMachineReadableToDesk -> 20d / 60,
                  eeaMachineReadableToEGate -> 35d / 60))
              )
            ))

            offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival))))

            val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
              VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
                manifestPax(10, euPassport)
              )
            )))

            offerAndWait(crunch.manifestsLiveInput, voyageManifests)

            val expected = Map(T1 -> Map(
              Queues.EeaDesk -> Seq(2.0, 0.0, 0.0, 0.0, 0.0),
              Queues.EGate -> Seq(8.0, 0.0, 0.0, 0.0, 0.0)
            ))

            crunch.portStateTestProbe.fishForMessage(5 seconds) {
              case ps: PortState =>
                paxLoadsFromPortState(ps, 5) == expected
            }

            success
          }
        }
      }
    }
  }
}
