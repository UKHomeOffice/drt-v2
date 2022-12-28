package feeds.acl

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator._
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.acl.AclFeed.{arrivalsFromCsvContent, delayUntilNextAclCheck, nextAclCheck}
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.{CrunchTestLike, TestConfig}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports.{AclFeedSource, LiveFeedSource, PortCode}

import scala.collection.immutable.{List, SortedMap}
import scala.concurrent.duration.DurationInt


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: Terminal => Terminal = (t: Terminal) => t

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
      val now = SDate("2021-10-15T10:00", Crunch.europeLondonTimeZone)
      "The next check should be 2021-10-15 18:00" >> {
        nextAclCheck(now, updateHour).toISOString() === "2021-10-15T18:00:00+01:00"
      }
      "The delay until the next check should be 8 hours" >> {
        val delay = delayUntilNextAclCheck(now, updateHour)
        delay === 8.hours
      }
    }

    "When the current date time is 2021-10-15 20:00" >> {
      val now = SDate("2021-10-15T20:00", Crunch.europeLondonTimeZone)
      "The next check should be 2021-10-16 18:00" >> {
        nextAclCheck(now, updateHour).toISOString() === "2021-10-16T18:00:00+01:00"
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
              """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(Arrival(
        Operator = Option(Operator("4U")),
        Status = ArrivalStatus("ACL Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Option(180),
        ActPax = Option(149),
        TranPax = None,
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LHR"),
        Terminal = T2,
        rawICAO = "4U0460",
        rawIATA = "4U0460",
        Origin = PortCode("CGN"),
        FeedSources = Set(AclFeedSource),
        Scheduled = 1507878600000L,
        PcpTime = None))

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
          PredictedTouchdown = None,
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
          FeedSources = Set(AclFeedSource),
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
          PredictedTouchdown = None,
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
          FeedSources = Set(AclFeedSource),
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

        val expected = Set(arrival.copy(FeedSources = Set(AclFeedSource), TotalPax = Set(TotalPaxSource(Option(10), AclFeedSource))))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
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
        val aclFlight = arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
        val aclFlights = Flights(List(aclFlight))
        val liveFlight = arrival(iata = "BAW001", schDt = scheduled, actPax = Option(20), actChoxDt = "2017-01-01T00:30Z")
        val liveFlights = Flights(List(liveFlight))

        val fiveMinutes = 600d / 60

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)))))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlights))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

        val expected = Set(liveFlight.copy(CarrierCode = aclFlight.CarrierCode,
          VoyageNumber = aclFlight.VoyageNumber, FeedSources = Set(AclFeedSource, LiveFeedSource),
          TotalPax = liveFlight.TotalPax ++ Set(TotalPaxSource(aclFlight.ActPax, AclFeedSource),
            TotalPaxSource(liveFlight.ActPax, LiveFeedSource))))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
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
          arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast")),
          arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast")))
        val initialLive = Set(
          arrival(iata = "BA0003", schDt = "2017-01-01T00:10Z", actPax = Option(99), status = ArrivalStatus("scheduled")))

        val newAcl = Set(
          arrival(iata = "BA0011", schDt = "2017-01-01T00:10Z", actPax = Option(105), status = ArrivalStatus("forecast")))

        val aclWithSource = initialAcl.map(_.copy(FeedSources = Set(AclFeedSource)))
        val liveWithSource = initialLive.map(_.copy(FeedSources = Set(LiveFeedSource)))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledLive),
          initialLiveArrivals = SortedMap[UniqueArrival, Arrival]() ++ initialLive.map(a => (a.unique, a)).toMap,
          initialForecastBaseArrivals = SortedMap[UniqueArrival, Arrival]() ++ initialAcl.map(a => (a.unique, a)).toMap,
          initialPortState = Option(PortState((aclWithSource ++ liveWithSource).map(a => ApiFlightWithSplits(a, Set())), Seq(), Seq())),
        ))


        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(newAcl.toList)))

        val expected: Set[Arrival] = initialLive
          .map(il => il.copy(FeedSources = Set(LiveFeedSource),
          )) ++
          newAcl.map(na => na.copy(FeedSources = Set(AclFeedSource),
            TotalPax = na.TotalPax ++ Set(TotalPaxSource(na.ActPax, AclFeedSource))))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
          case ps: PortState =>
            ps.flights.values.map(_.apiFlight).toSet == expected
        }

        success
      }

      "Given some initial arrivals, no ACL arrivals and one live arrival " +
        "When I ask for a crunch " +
        "Then I should only see the initial arrivals updated with the live arrival" >> {
        val scheduledLive = "2017-01-01T00:00Z"

        val live = arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = ArrivalStatus("scheduled"))
        val initialAcl1 = arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"))
        val initialAcl2 = arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast"))

        val initialAcl = Set(initialAcl1, initialAcl2)
        val initialLive = SortedMap[UniqueArrival, Arrival]() ++ List(live).map(a => (a.unique, a))

        val liveArrival = arrival(iata = "BAW0001", schDt = "2017-01-01T00:05Z", actPax = Option(105), status = ArrivalStatus("estimated"), estDt = "2017-01-01T00:06Z")
        val newLive = Set(
          arrival(iata = "BAW0001", schDt = "2017-01-01T00:05Z", actPax = Option(105), status = ArrivalStatus("estimated"), estDt = "2017-01-01T00:06Z"))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledLive),
          initialLiveArrivals = initialLive,
          initialPortState = Option(PortState(Seq(live, initialAcl1, initialAcl2).map(a => ApiFlightWithSplits(a, Set())), Seq(), Seq())),
        ))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(newLive.toList)))

        val expected = newLive.map(_.copy(
          CarrierCode = CarrierCode("BA"),
          FeedSources = Set(LiveFeedSource, AclFeedSource),
          TotalPax = Set(TotalPaxSource(liveArrival.ActPax, LiveFeedSource), TotalPaxSource(initialAcl1.ActPax, AclFeedSource)))
        ) + initialAcl2.copy(
          FeedSources = Set(AclFeedSource),
          TotalPax = Set(TotalPaxSource(initialAcl2.ActPax, AclFeedSource)),
        )

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
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

        val live = arrival(iata = "BA0003", schDt = "2017-01-01T00:03Z", actPax = Option(99), status = ArrivalStatus("scheduled"))
        val initialAcl1 = arrival(iata = "BA0001", schDt = "2017-01-01T00:01Z", actPax = Option(150), status = ArrivalStatus("forecast"))
        val initialAcl2 = arrival(iata = "BA0002", schDt = "2017-01-01T00:02Z", actPax = Option(151), status = ArrivalStatus("forecast"))

        val initialLive = SortedMap[UniqueArrival, Arrival]() ++ List(live).map(a => (a.unique, a))
        val initialAcl = SortedMap[UniqueArrival, Arrival]() ++ List(initialAcl1, initialAcl2).map(a => (a.unique, a))

        val newAcl = Flights(Seq(arrival(iata = "BA0004", schDt = "2017-01-01T00:04Z", actPax = Option(100))))
        val newLive = Flights(Seq(arrival(iata = "BAW0005", schDt = "2017-01-01T00:05Z", actPax = Option(101))))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledLive),
          initialForecastBaseArrivals = initialAcl,
          initialLiveArrivals = initialLive,
          initialPortState = Option(PortState(Seq(live, initialAcl1, initialAcl2).map(a => ApiFlightWithSplits(a, Set())), Seq(), Seq())),
        ))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(newAcl))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

        val expected = Set(VoyageNumber(3), VoyageNumber(4), VoyageNumber(5))

        crunch.portStateTestProbe.fishForMessage(5.seconds) {
          case ps: PortState =>
            ps.flights.values.map(_.apiFlight.VoyageNumber).toSet == expected
        }

        success
      }

      "Given one ACL arrival followed by the same single ACL arrival " +
        "When I ask for a crunch " +
        "Then I should still see the arrival, ie it should not have been removed" >> {
        val scheduledLive = "2017-01-01T00:00Z"

        val aclArrival = arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"), feedSources = Set(AclFeedSource))

        val aclInput1 = Flights(Seq(aclArrival))
        val aclInput2 = Flights(Seq(aclArrival))

        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduledLive)))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput1))
        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclInput2))

        val portStateFlightLists = crunch.portStateTestProbe.receiveWhile(3.seconds) {
          case PortState(f, _, _) => f.values.map(_.apiFlight)
        }

        val nonEmptyFlightsList = List(aclArrival.copy(FeedSources = Set(AclFeedSource), TotalPax = Set(TotalPaxSource(aclArrival.ActPax, AclFeedSource))))
        val expected = List(nonEmptyFlightsList)

        portStateFlightLists.distinct === expected
      }
    }
  }
}
