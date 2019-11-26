package services.inputfeeds

import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals._
import drt.shared._
import server.feeds.acl.AclFeed
import server.feeds.acl.AclFeed._
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: Terminal => Terminal = (t: Terminal) => t
  val lgwTerminalMapping: Terminal => Terminal = (t: Terminal) => Map[Terminal, Terminal](T2 -> S).getOrElse(t, InvalidTerminal)

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "badusername", "badpath", "BAD", (t: Terminal) => T1)

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
      val expected = List(Arrival(Operator = Some("4U"), Status = "ACL Forecast", Estimated = None, Actual = None,
        EstimatedChox = None, ActualChox = None, Gate = None, Stand = None, MaxPax = Some(180), ActPax = Some(149),
        TranPax = None, RunwayID = None, BaggageReclaimId = None, AirportID = "LHR", Terminal = T2,
        rawICAO = "4U0460", rawIATA = "4U0460", Origin = "CGN",FeedSources = Set(shared.AclFeedSource),
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

    "Given ACL csv content containing a header line and one flight with an unmapped terminal name " +
      "When I ask for the arrivals " +
      "Then I should see the arrival with its mapped terminal name" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, lgwTerminalMapping)
      val expected = List(Arrival(Operator = Some("4U"), Status = "ACL Forecast", Estimated = None,
        Actual = None, EstimatedChox = None, ActualChox = None, Gate = None,
        Stand = None, MaxPax = Some(180), ActPax = Some(149), TranPax = None, RunwayID = None, BaggageReclaimId = None,
        AirportID = "LHR", Terminal = S, rawICAO = "4U0460", rawIATA = "4U0460",
        Origin = "CGN", Scheduled = 1507878600000L, PcpTime = None, FeedSources = Set(shared.AclFeedSource)))

      arrivals === expected
    }
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the PortState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
      val aclFlight = Flights(List(arrival))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val expected = Set(arrival.copy(FeedSources = Set(AclFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

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

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlights))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

      val expected = Set(liveFlight.copy(rawIATA = aclFlight.rawIATA, rawICAO = aclFlight.rawICAO, FeedSources = Set(AclFeedSource, LiveFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given some initial ACL & live arrivals, one ACL arrival and no live arrivals " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL arrival with the initial live arrivals" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl = Set(
        ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = "forecast"),
        ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = "forecast"))
      val initialLive = Set(
        ArrivalGenerator.arrival(iata = "BA0003", schDt = "2017-01-01T00:25Z", actPax = Option(99), status = "scheduled"))

      val newAcl = Set(
        ArrivalGenerator.arrival(iata = "BA0011", schDt = "2017-01-01T00:10Z", actPax = Option(105), status = "forecast"))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(initialLive.toList)))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(newAcl.toList)))

      val expected = initialLive.map(_.copy(FeedSources = Set(LiveFeedSource))) ++ newAcl.map(_.copy(FeedSources = Set(AclFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = "forecast")
      val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = "forecast")
      val initialAcl = Set(initialAcl1, initialAcl2)
      val initialLive = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = "scheduled")).map(a => (a.unique, a))

      val newLive = Set(
        ArrivalGenerator.arrival(iata = "BAW0001", schDt = "2017-01-01T00:05Z", actPax = Option(105), status = "estimated", estDt = "2017-01-01T00:06Z"))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduledLive),
        initialLiveArrivals = initialLive
      )

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(newLive.toList)))

      val expected = newLive.map(_.copy(rawIATA = "BA0001", FeedSources = Set(LiveFeedSource, AclFeedSource))) + initialAcl2.copy(FeedSources = Set(AclFeedSource))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL & live arrivals plus the initial live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = "forecast")
      val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = "forecast")
      val initialAcl = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(initialAcl1, initialAcl2).map(a => (a.unique, a))
      val initialLive = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = "scheduled")).map(a => (a.unique, a))

      val newAcl = Flights(Seq(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:07Z", actPax = Option(105))))
      val newLive = Flights(Seq(ArrivalGenerator.arrival(iata = "BAW0001", schDt = "2017-01-01T00:06Z", actPax = Option(105))))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduledLive),
        initialForecastBaseArrivals = initialAcl,
        initialLiveArrivals = initialLive
      )

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(newAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

      val expected = newAcl.flights.map(_.copy(FeedSources = Set(AclFeedSource))).toSet ++
        newLive.flights.map(_.copy(FeedSources = Set(LiveFeedSource))).toSet ++
        initialLive.values.map(_.copy(FeedSources = Set(LiveFeedSource)))

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given one ACL arrival followed by the same single ACL arrival " +
      "When I ask for a crunch " +
      "Then I should still see the arrival, ie it should not have been removed" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val aclArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = "forecast", feedSources = Set(AclFeedSource))

      val aclInput1 = Flights(Seq(aclArrival))
      val aclInput2 = Flights(Seq(aclArrival))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput1))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput2))

      val portStateFlightLists = crunch.portStateTestProbe.receiveWhile(3 seconds) {
        case PortState(f, _, _) => f.values.map(_.apiFlight)
      }

      val nonEmptyFlightsList = List(aclArrival.copy(FeedSources = Set(AclFeedSource)))
      val expected = List(nonEmptyFlightsList)

      crunch.liveArrivalsInput.complete()

      portStateFlightLists.distinct === expected
    }
  }

  "Looking at flights" >> {
    skipped("Integration test for ACL - requires SSL certificate to run")
    val ftpServer = ConfigFactory.load.getString("acl.host")
    val username = ConfigFactory.load.getString("acl.username")
    val path = ConfigFactory.load.getString("acl.keypath")

    val sftp = sftpClient(sshClient(ftpServer, username, path))
    val latestFile = latestFileForPort(sftp, "MAN")
    println(s"latestFile: $latestFile")
    val aclArrivals: List[Arrival] = arrivalsFromCsvContent(contentFromFileName(sftp, latestFile), regularTerminalMapping)

    val todayArrivals = aclArrivals
      .filter(_.Scheduled < SDate("2017-10-05T23:00").millisSinceEpoch)
      .groupBy(_.Terminal)

    todayArrivals.foreach {
      case (tn, _) =>
        val tByUniqueId = todayArrivals(tn).groupBy(_.uniqueId)
        println(s"uniques for $tn: ${tByUniqueId.keys.size} flights")
        tByUniqueId.filter {
          case (_, a) => a.length > 1
        }.foreach {
          case (uid, a) => println(s"non-unique: $uid -> $a")
        }
    }

    todayArrivals.keys.foreach(t => println(s"terminal $t has ${todayArrivals(t).size} flights"))

    success
  }
}
