package feeds.acl

import controllers.ArrivalGenerator
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.acl.AclFeed.{aclFileName, arrivalsFromCsvContent}
import drt.shared
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals._
import drt.shared._
import drt.shared.api.Arrival
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.{CrunchTestLike, TestConfig}

import scala.collection.immutable.{List, SortedMap}
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: Terminal => Terminal = (t: Terminal) => t

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "badusername", "badpath", PortCode("BAD"), (_: Terminal) => T1)

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
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(Arrival(Operator = Option(Operator("4U")), Status = ArrivalStatus("ACL Forecast"), Estimated = None, Actual = None,
        EstimatedChox = None, ActualChox = None, Gate = None, Stand = None, MaxPax = Option(180), ActPax = Option(149),
        TranPax = None, RunwayID = None, BaggageReclaimId = None, AirportID = PortCode("LHR"), Terminal = T2,
        rawICAO = "4U0460", rawIATA = "4U0460", Origin = PortCode("CGN"), FeedSources = Set(shared.AclFeedSource),
        Scheduled = 1507878600000L, PcpTime = None))

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one departure line " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

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
        """.stripMargin

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
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(
        Arrival(
          Operator = Option(Operator("4U")),
          Status = ArrivalStatus("ACL Forecast"),
          Estimated = None,
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = Option(0),
          ActPax = Option(0),
          TranPax = None,
          RunwayID = None,
          BaggageReclaimId = None,
          AirportID = PortCode("LHR"),
          Terminal = T2,
          rawICAO = "4U0460",
          rawIATA = "4U0460",
          Origin = PortCode("CGN"),
          FeedSources = Set(shared.AclFeedSource),
          Scheduled = 1507878600000L,
          PcpTime = None))

      arrivals === expected
    }

    "Given ACL csv content containing  200 for MaxPax but 0 for load factor " +
      "When I ask for the arrivals " +
      "Then I should see 0 being used for act pax and 200 for Max Pax" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,200,0460,J,,2I,0710,4U,0461,4U0460,0
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(
        Arrival(
          Operator = Option(Operator("4U")),
          Status = ArrivalStatus("ACL Forecast"),
          Estimated = None,
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = Option(200),
          ActPax = Option(0),
          TranPax = None,
          RunwayID = None,
          BaggageReclaimId = None,
          AirportID = PortCode("LHR"),
          Terminal = T2,
          rawICAO = "4U0460",
          rawIATA = "4U0460",
          Origin = PortCode("CGN"),
          FeedSources = Set(shared.AclFeedSource),
          Scheduled = 1507878600000L,
          PcpTime = None))

      arrivals === expected
    }

    "ACL Flights " >> {
      "Given an ACL feed with one flight and no live flights" +
        "When I ask for a crunch " +
        "Then I should see that flight in the PortState" >> {
        val scheduled = "2017-01-01T00:00Z"
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
        val aclFlight = Flights(List(arrival))

        val fiveMinutes = 600d / 60

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)))))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))

        val expected = Set(arrival.copy(FeedSources = Set(AclFeedSource)))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsResult = ps.flights.values.map(_.apiFlight).toSet
            flightsResult == expected
        }

        success
      }

      "Given an ACL feed with one flight and the same flight in the live feed" +
        "When I ask for a crunch " +
        "Then I should see the one flight in the PortState with the ACL flightcode and live chox" >> {
        val scheduled = "2017-01-01T00:00Z"
        val aclFlight = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
        val aclFlights = Flights(List(aclFlight))
        val liveFlight = ArrivalGenerator.arrival(iata = "BAW001", schDt = scheduled, actPax = Option(20), actChoxDt = "2017-01-01T00:30Z")
        val liveFlights = Flights(List(liveFlight))

        val fiveMinutes = 600d / 60

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)))))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlights))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

        val expected = Set(liveFlight.copy(CarrierCode = aclFlight.CarrierCode, VoyageNumber = aclFlight.VoyageNumber, FeedSources = Set(AclFeedSource, LiveFeedSource)))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsResult = ps.flights.values.map(_.apiFlight).toSet
            flightsResult == expected
        }

        success
      }

      "Given some initial ACL & live arrivals, one ACL arrival and no live arrivals " +
        "When I ask for a crunch " +
        "Then I should only see the new ACL arrival with the initial live arrivals" >> {
        val scheduledLive = "2017-01-01T00:00Z"

        val initialAcl = Set(
          ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast")),
          ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast")))
        val initialLive = Set(
          ArrivalGenerator.arrival(iata = "BA0003", schDt = "2017-01-01T00:25Z", actPax = Option(99), status = ArrivalStatus("scheduled")))

        val newAcl = Set(
          ArrivalGenerator.arrival(iata = "BA0011", schDt = "2017-01-01T00:10Z", actPax = Option(105), status = ArrivalStatus("forecast")))

        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduledLive)))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(initialLive.toList)))
        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(newAcl.toList)))

        val expected = initialLive.map(_.copy(FeedSources = Set(LiveFeedSource))) ++ newAcl.map(_.copy(FeedSources = Set(AclFeedSource)))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsResult = ps.flights.values.map(_.apiFlight).toSet
            flightsResult == expected
        }

        success
      }

      "Given some initial arrivals, no ACL arrivals and one live arrival " +
        "When I ask for a crunch " +
        "Then I should only see the initial arrivals updated with the live arrival" >> {
        val scheduledLive = "2017-01-01T00:00Z"

        val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"))
        val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast"))
        val initialAcl = Set(initialAcl1, initialAcl2)
        val initialLive = SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = ArrivalStatus("scheduled"))).map(a => (a.unique, a))

        val newLive = Set(
          ArrivalGenerator.arrival(iata = "BAW0001", schDt = "2017-01-01T00:05Z", actPax = Option(105), status = ArrivalStatus("estimated"), estDt = "2017-01-01T00:06Z"))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledLive),
          initialLiveArrivals = initialLive
        ))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(newLive.toList)))

        val expected = newLive.map(_.copy(CarrierCode = CarrierCode("BA"), VoyageNumber = VoyageNumber(1), FeedSources = Set(LiveFeedSource, AclFeedSource))) + initialAcl2.copy(FeedSources = Set(AclFeedSource))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsResult = ps.flights.values.map(_.apiFlight).toSet
            flightsResult == expected
        }

        success
      }

      "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
        "When I ask for a crunch " +
        "Then I should only see the new ACL & live arrivals plus the initial live arrival" >> {
        val scheduledLive = "2017-01-01T00:00Z"

        val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:01Z", actPax = Option(150), status = ArrivalStatus("forecast"))
        val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:02Z", actPax = Option(151), status = ArrivalStatus("forecast"))
        val initialAcl = SortedMap[UniqueArrival, Arrival]() ++ List(initialAcl1, initialAcl2).map(a => (a.unique, a))
        val initialLive = SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0003", schDt = "2017-01-01T00:03Z", actPax = Option(99), status = ArrivalStatus("scheduled"))).map(a => (a.unique, a))

        val newAcl = Flights(Seq(ArrivalGenerator.arrival(iata = "BA0004", schDt = "2017-01-01T00:04Z", actPax = Option(100))))
        val newLive = Flights(Seq(ArrivalGenerator.arrival(iata = "BAW0005", schDt = "2017-01-01T00:05Z", actPax = Option(101))))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledLive),
          initialForecastBaseArrivals = initialAcl,
          initialLiveArrivals = initialLive
        ))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(newAcl))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

        val expected = Set(VoyageNumber(3), VoyageNumber(4), VoyageNumber(5))

        crunch.portStateTestProbe.fishForMessage(5 seconds) {
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

        val aclArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"), feedSources = Set(AclFeedSource))

        val aclInput1 = Flights(Seq(aclArrival))
        val aclInput2 = Flights(Seq(aclArrival))

        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduledLive)))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput1))
        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput2))

        val portStateFlightLists = crunch.portStateTestProbe.receiveWhile(3 seconds) {
          case PortState(f, _, _) => f.values.map(_.apiFlight)
        }

        val nonEmptyFlightsList = List(aclArrival.copy(FeedSources = Set(AclFeedSource)))
        val expected = List(nonEmptyFlightsList)

        portStateFlightLists.distinct === expected
      }
    }
  }

  "I should be able to construct an ACL file name given the date, port code and season, regardless of case" >> {
    val today = SDate("2021-09-27T12:00")
    val portCode = PortCode("bhd")
    val season = "w"

    val file: String = aclFileName(today, portCode, season)

    file === "BHDW21_HOMEOFFICEROLL180_20210927.zip"
  }

  "The list of latest possible acl file dates and seasons should be in reverse date order" >> {
    skipped("integration test")

    val todayMidnight = SDate.now().getUtcLastMidnight
    val yesterdayMidnight = todayMidnight.addDays(-1)
    val aclFeed = AclFeed("mock-server", "mock-username", "mock-path", PortCode("BHD"), t => t)

    aclFeed.latestFileDateAndSeason(PortCode("BHD")) === List((todayMidnight, "S"),(todayMidnight, "W"),(yesterdayMidnight, "S"), (yesterdayMidnight, "W"))
  }
}
