package services.inputfeeds

import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import server.feeds.acl.AclFeed
import server.feeds.acl.AclFeed._
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable.List
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: TerminalName => TerminalName = (t: TerminalName) => s"T${t.take(1)}"
  val lgwTerminalMapping: TerminalName => TerminalName = (t: TerminalName) => Map("2I" -> "S").getOrElse(t, "")

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "badusername", "badpath", "BAD", (t: TerminalName) => "T1")

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
        TranPax = None, RunwayID = None, BaggageReclaimId = None, FlightID = Some(-904483842), AirportID = "LHR", Terminal = "T2",
        rawICAO = "4U0460", rawIATA = "4U0460", Origin = "CGN",FeedSources = Set(shared.AclFeedSource),
        Scheduled = 1507878600000L, PcpTime = None, LastKnownPax = None))

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
        FlightID = Some(-904483842), AirportID = "LHR", Terminal = "S", rawICAO = "4U0460", rawIATA = "4U0460",
        Origin = "CGN", Scheduled = 1507878600000L, PcpTime = None, FeedSources = Set(shared.AclFeedSource), LastKnownPax = None))

      arrivals === expected
    }
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the CrunchState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val arrival = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(10), schDt = scheduled, iata = "BA0001")
      val aclFlight = Flights(List(arrival))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val expected = Set(arrival.copy(FeedSources = Set(AclFeedSource)))

      crunch.liveTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }

    "Given an ACL feed with one flight and the same flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the one flight in the CrunchState with the ACL flightcode and live chox" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(10), schDt = scheduled, iata = "BA0001")
      val aclFlights = Flights(List(aclFlight))
      val liveFlight = ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(20), schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z")
      val liveFlights = Flights(List(liveFlight))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlights))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

      val expected = Set(liveFlight.copy(rawIATA = aclFlight.rawIATA, rawICAO = aclFlight.rawICAO, FeedSources = Set(AclFeedSource, LiveFeedSource)))

      crunch.liveTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }

    "Given some initial ACL & live arrivals, one ACL arrival and no live arrivals " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL arrival with the initial live arrivals" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialACL = Flights(Seq(
        ArrivalGenerator.apiFlight(actPax = Option(150), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast"),
        ArrivalGenerator.apiFlight(actPax = Option(151), schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")))
      val initialLive = Flights(Seq(
        ArrivalGenerator.apiFlight(actPax = Option(99), schDt = "2017-01-01T00:25Z", iata = "BA0003", status = "scheduled")))

      val newAcl = Set(
        ArrivalGenerator.apiFlight(actPax = Option(105), schDt = "2017-01-01T00:10Z", iata = "BA0011", status = "forecast"))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(initialACL))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(initialLive))

      Thread.sleep(500) // Let the initial arrivals work their way through the system
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(newAcl.toList)))

      val expected = initialLive.flights.map(_.copy(FeedSources = Set(LiveFeedSource))).toSet ++ newAcl.map(_.copy(FeedSources = Set(AclFeedSource)))

      crunch.liveTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.apiFlight(actPax = Option(150), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast")
      val initialAcl2 = ArrivalGenerator.apiFlight(actPax = Option(151), schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")
      val initialAcl = Flights(Seq(initialAcl1, initialAcl2))
      val initialLive = Flights(Seq(ArrivalGenerator.apiFlight(actPax = Option(99), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "scheduled")))

      val newLive = Set(
        ArrivalGenerator.apiFlight(actPax = Option(105), schDt = "2017-01-01T00:05Z", estDt = "2017-01-01T00:06Z", iata = "BAW0001", status = "estimated"))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(initialAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(initialLive))

      Thread.sleep(500) // Let the initial arrivals work their way through the system

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(newLive.toList)))

      val expected = newLive.map(_.copy(rawIATA = "BA0001", FeedSources = Set(LiveFeedSource, AclFeedSource))) + initialAcl2.copy(FeedSources = Set(AclFeedSource))

      crunch.liveTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }

    "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL & live arrivals plus the initial live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.apiFlight(actPax = Option(150), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast")
      val initialAcl2 = ArrivalGenerator.apiFlight(actPax = Option(151), schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")
      val initialAcl = Flights(Seq(initialAcl1, initialAcl2))
      val initialLive = Flights(Seq(ArrivalGenerator.apiFlight(actPax = Option(99), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "scheduled")))

      val newAcl = Flights(Seq(ArrivalGenerator.apiFlight(actPax = Option(105), schDt = "2017-01-01T00:07Z", iata = "BA0001")))
      val newLive = Flights(Seq(ArrivalGenerator.apiFlight(actPax = Option(105), schDt = "2017-01-01T00:06Z", iata = "BAW0001")))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(initialAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(initialLive))

      Thread.sleep(500) // Let the initial arrivals work their way through the system

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(newAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

      val expected = newAcl.flights.map(_.copy(FeedSources = Set(AclFeedSource))).toSet ++
        newLive.flights.map(_.copy(FeedSources = Set(LiveFeedSource))).toSet ++
        initialLive.flights.map(_.copy(FeedSources = Set(LiveFeedSource))).toSet

      crunch.liveTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }

    "Given one ACL arrival followed by the same single ACL arrival " +
      "When I ask for a crunch " +
      "Then I should still see the arrival, ie it should not have been removed" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val aclArrival = ArrivalGenerator.apiFlight(actPax = Option(150), schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast", feedSources = Set(AclFeedSource))

      val aclInput1 = Flights(Seq(aclArrival))
      val aclInput2 = Flights(Seq(aclArrival))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput1))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput2))

      val portStateFlightLists = crunch.liveTestProbe.receiveWhile(3 seconds) {
        case PortState(f, _, _) => f.values.map(_.apiFlight)
      }

      val nonEmptyFlightsList = List(aclArrival.copy(FeedSources = Set(AclFeedSource)))
      val expected = List(nonEmptyFlightsList)

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

    true
  }
}
