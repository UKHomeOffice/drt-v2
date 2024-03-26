package feeds.acl

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator._
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.acl.AclFeed.{arrivalsFromCsvContent, delayUntilNextAclCheck, nextAclCheck}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import drt.shared._
import services.crunch.{CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports.{AclFeedSource, ForecastFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: Terminal => Terminal = (t: Terminal) => t

  "Given ACL flights and an pax figures adjustment" >> {
    "When I import ACL I should see the expected Total and PCP pax figures" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val aclFlights = Seq(forecast(iata = "BA0004", schDt = "2017-01-01T00:04Z", totalPax = Option(100)))
      val forecastFlights = Seq(forecast(iata = "BA0004", schDt = "2017-01-01T00:04Z", totalPax = Option(50)))

      var adjustment = 1

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduledLive),
        passengerAdjustments = arrivals => Future.successful(
          arrivals.map {
            case a: LiveArrival => a.copy(totalPax = a.totalPax.map(_ + adjustment))
            case a: ForecastArrival => a.copy(totalPax = a.totalPax.map(_ + adjustment))
          }
        )))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlights))
      offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastFlights))
      adjustment = 5
      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlights))

      crunch.portStateTestProbe.fishForMessage(5.seconds) {
        case PortState(flights, _, _) if flights.nonEmpty =>
          val flight = flights.head._2.apiFlight
          val actPaxOk = flight.bestPcpPaxEstimate(paxFeedSourceOrder) == Option(50)
          val totalPaxOk = flight.PassengerSources == Map(
            AclFeedSource -> Passengers(Option(105), None),
            ForecastFeedSource -> Passengers(Option(50), None),
          )
          actPaxOk && totalPaxOk
        case _ => false
      }

      success
    }
  }

  "Given a date, a port, and a list of files" >> {
    val today = SDate("2022-01-04T10:00")
    val todayFile = "LHRW21_HOMEOFFICEROLL180_20220104.zip"
    val todayWrongPortFile = "BHXW21_HOMEOFFICEROLL180_20220104.zip"
    val yesterdayFile = "LHRW21_HOMEOFFICEROLL180_20220103.zip"
    val twoDaysAgoFile = "LHRW21_HOMEOFFICEROLL180_20220102.zip"
    val port = "LHR"
    "If the list of files does not contain a match for the port in question for a recent date then I should get None" >> {
      val files = List()
      AclFeed.maybeLatestFile(files, port, today) === None
    }

    "If the list of files contains a match for today at the port in question then I should get it's filename" >> {
      val files = List(todayFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(todayFile)
    }

    "If the list of files has yesterday's followed by today's for the port, then I should get today's" >> {
      val files = List(yesterdayFile, todayFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(todayFile)
    }

    "If the list of files has today's followed by yesterday's for the port, then I should get today's" >> {
      val files = List(todayFile, yesterdayFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(todayFile)
    }

    "If the list of files has 2 days ago's followed by yesterday's for the port, then I should get yesterday's" >> {
      val files = List(twoDaysAgoFile, yesterdayFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(yesterdayFile)
    }

    "If the list of files has today's for our port followed by the wrong port, then I should get today's for our port" >> {
      val files = List(todayWrongPortFile, todayFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(todayFile)
    }

    "If the list of files has today's for the wrong port followed by our port, then I should get today's for our port" >> {
      val files = List(todayFile, todayWrongPortFile)
      AclFeed.maybeLatestFile(files, port, today) === Option(todayFile)
    }
  }

  "Given ACL updates are available at 18:00 UK time" >> {
    val updateHour = 18

    "When the current date time is 2021-10-15 10:00" >> {
      val now = SDate("2021-10-15T10:00", europeLondonTimeZone)
      "The next check should be 2021-10-15 18:00" >> {
        nextAclCheck(now, updateHour).toISOString === "2021-10-15T18:00:00+01:00"
      }
      "The delay until the next check should be 8 hours" >> {
        val delay = delayUntilNextAclCheck(now, updateHour)
        delay === 8.hours
      }
    }

    "When the current date time is 2021-10-15 20:00" >> {
      val now = SDate("2021-10-15T20:00", europeLondonTimeZone)
      "The next check should be 2021-10-16 18:00" >> {
        nextAclCheck(now, updateHour).toISOString === "2021-10-16T18:00:00+01:00"
      }
      "The delay until the next check should be 22 hours" >> {
        val delay = delayUntilNextAclCheck(now, updateHour)
        delay === 22.hours
      }
    }
  }

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "bad username", "bad path", PortCode("BAD"), (_: Terminal) => T1)

    val result = aclFeed.requestArrivals.getClass

    val expected = classOf[ArrivalsFeedFailure]

    result === expected
  }

  "ACL feed parsing" >> {

    "Given ACL csv content containing a header line and one arrival line " +
      "When I ask for the arrivals " +
      "Then I should see a list containing the appropriate Arrival" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
          |""".stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(ForecastArrival(
        operator = Option("4U"),
        maxPax = Option(180),
        totalPax = Option(149),
        transPax = None,
        terminal = T2,
        voyageNumber = 460,
        carrierCode = "4U",
        flightCodeSuffix = None,
        origin = "CGN",
        scheduled = 1507878600000L,
      ))

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one departure line " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
          |""".stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List()

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one positioning flight " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460P,0.827777802944183
          |""".stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List()

      arrivals === expected
    }

    "Given ACL csv content containing 0 for MaxPax and 0 for load factor " +
      "When I ask for the arrivals " +
      "Then I should see 0 being used for both Max and Act pax" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,0,0460,J,,2I,0710,4U,0461,4U0460,0
          |""".stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(ForecastArrival(
        operator = Option("4U"),
        maxPax = Option(0),
        totalPax = Option(0),
        transPax = None,
        terminal = T2,
        voyageNumber = 460,
        carrierCode = "4U",
        flightCodeSuffix = None,
        origin = "CGN",
        scheduled = 1507878600000L,
      ))
      arrivals === expected
    }

    "Given ACL csv content containing  200 for MaxPax but 0 for load factor " +
      "When I ask for the arrivals " +
      "Then I should see 0 being used for act pax and 200 for Max Pax" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,200,0460,J,,2I,0710,4U,0461,4U0460,0
          |""".stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(ForecastArrival(
        operator = Option("4U"),
        maxPax = Option(200),
        totalPax = Option(0),
        transPax = None,
        terminal = T2,
        voyageNumber = 460,
        carrierCode = "4U",
        flightCodeSuffix = None,
        origin = "CGN",
        scheduled = 1507878600000L,
      ))
      arrivals === expected
    }
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the PortState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val arrival = ArrivalGenerator.forecast(iata = "BA0001", schDt = scheduled, totalPax = Option(10))
      val aclFlight = List(arrival)

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)))))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val expected = Set(arrival.unique)

      crunch.portStateTestProbe.fishForMessage(3.seconds) {
        case PortState(flights, _, _) =>
          val flightsResult = flights.values.map(_.unique).toSet
          flightsResult == expected
      }

      success
    }

    "Given an ACL feed with one flight and the same flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the one flight in the PortState with the ACL flightcode and live chox" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = forecast(iata = "BA0001", schDt = scheduled, totalPax = Option(10))
      val aclFlights = List(aclFlight)
      val liveFlight = live(iata = "BAW001", schDt = scheduled, totalPax = Option(20), actChoxDt = "2017-01-01T00:30Z")
      val liveFlights = List(liveFlight)

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)))))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlights))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

      val expected = Set(Option(SDate("2017-01-01T00:30Z").millisSinceEpoch))

      crunch.portStateTestProbe.fishForMessage(3.seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight.ActualChox).toSet
          flightsResult == expected
      }

      success
    }

    "Given some initial ACL & live arrivals, one ACL arrival and no live arrivals " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL arrival with the initial live arrivals" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl = Seq(
        forecast(iata = "BA0001", schDt = "2017-01-01T00:05Z", totalPax = Option(150)),
        forecast(iata = "BA0002", schDt = "2017-01-01T00:15Z", totalPax = Option(151)))
      val initialLive = Seq(live(iata = "BA0003", schDt = "2017-01-01T00:10Z", totalPax = Option(99), status = ArrivalStatus("scheduled")))

      val newAcl = Seq(forecast(iata = "BA0011", schDt = "2017-01-01T00:10Z", totalPax = Option(105)))

      val aclWithSource = initialAcl.map(_.toArrival(AclFeedSource))
      val liveWithSource = initialLive.map(_.toArrival(LiveFeedSource))

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduledLive),
        initialLiveArrivals = initialLive,
        initialForecastBaseArrivals = initialAcl,
        initialPortState = Option(PortState((aclWithSource ++ liveWithSource).map(a => ApiFlightWithSplits(a, Set())), Seq(), Seq()))
      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(newAcl))

      val expected = newAcl.map(_.unique).toSet

      crunch.portStateTestProbe.fishForMessage(3.seconds) {
        case ps: PortState =>
          ps.flights.values.map(_.apiFlight.unique).toSet == expected
      }

      success
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = forecast(iata = "BA0001", schDt = "2017-01-01T00:05Z", totalPax = Option(150))
      val initialAcl2 = forecast(iata = "BA0002", schDt = "2017-01-01T00:15Z", totalPax = Option(151))

      val initialAcl = Set(initialAcl1, initialAcl2)

      val liveArrival = live(iata = "BAW0001", schDt = "2017-01-01T00:05Z", totalPax = Option(105),
        status = ArrivalStatus("estimated"), estDt = "2017-01-01T00:06Z")
      val newLive = Set(liveArrival)

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduledLive),
      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(initialAcl.toList))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive.toList))

      val expected = newLive.map(_.unique) + initialAcl2.unique

      crunch.portStateTestProbe.fishForMessage(3.seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight.unique).toSet
          flightsResult == expected
      }

      success
    }

    "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL & live arrivals plus the initial live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val live = ArrivalGenerator.live(iata = "BA0003", schDt = "2017-01-01T00:03Z", totalPax = Option(99), status = ArrivalStatus("scheduled"))
      val initialAcl1 = ArrivalGenerator.forecast(iata = "BA0001", schDt = "2017-01-01T00:01Z", totalPax = Option(150))
      val initialAcl2 = ArrivalGenerator.forecast(iata = "BA0002", schDt = "2017-01-01T00:02Z", totalPax = Option(151))

      val initialLive = List(live)
      val initialAcl = List(initialAcl1, initialAcl2)

      val newAcl = Seq(ArrivalGenerator.forecast(iata = "BA0004", schDt = "2017-01-01T00:04Z", totalPax = Option(100)))
      val newLive = Seq(ArrivalGenerator.live(iata = "BAW0005", schDt = "2017-01-01T00:05Z", totalPax = Option(101)))

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduledLive),
        initialForecastBaseArrivals = initialAcl,
        initialLiveArrivals = initialLive,
        initialPortState = Option(PortState(Seq(
          live.toArrival(LiveFeedSource), initialAcl1.toArrival(AclFeedSource), initialAcl2.toArrival(AclFeedSource)
        ).map(a => ApiFlightWithSplits(a.copy(PcpTime = Option(a.Scheduled)), Set())), Seq(), Seq())),
      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(newAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

      val expected = Set(VoyageNumber(3), VoyageNumber(4), VoyageNumber(5))

      crunch.portStateTestProbe.fishForMessage(5.seconds) {
        case ps: PortState =>
          val voyageNos = ps.flights.values.map(_.apiFlight.VoyageNumber).toSet
          voyageNos == expected
      }

      success
    }

    "Given one ACL arrival followed by the same single ACL arrival " +
      "When I ask for a crunch " +
      "Then I should still see the arrival, ie it should not have been removed" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val aclArrival = forecast(iata = "BA0001", schDt = "2017-01-01T00:05Z", totalPax = Option(150))

      val aclInput1 = Seq(aclArrival)
      val aclInput2 = Seq(aclArrival)

      val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduledLive)))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput1))
      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput2))

      val portStateFlightLists = crunch.portStateTestProbe.receiveWhile(3.seconds) {
        case PortState(f, _, _) => f.values.map(_.apiFlight)
      }

      val nonEmptyFlightsList = List(aclArrival.toArrival(AclFeedSource).copy(PcpTime = Option(SDate("2017-01-01T00:05Z").millisSinceEpoch)))

      portStateFlightLists.flatten.distinct === nonEmptyFlightsList
    }
  }
}
