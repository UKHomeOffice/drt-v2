package services.inputfeeds

import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import net.schmizz.sshj.sftp.SFTPClient
import server.feeds.acl.AclFeed
import server.feeds.acl.AclFeed.{arrivalsFromCsvContent, contentFromFileName, latestFileForPort, sftpClient}
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable.List
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: TerminalName => TerminalName = (t: TerminalName) => s"T${t.take(1)}"
  val lgwTerminalMapping: TerminalName => TerminalName = (t: TerminalName) => Map("2I" -> "S").getOrElse(t, "")

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "badusername", "badpath", "BAD", (t: TerminalName) => "T1")

    val result = aclFeed.arrivals

    val expected = None

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
      val expected = List(Arrival("4U", "ACL Forecast", "", "", "", "", "", "", 180, 149, 0, "", "", -904483842, "LHR", "T2", "4U0460", "4U0460", "CGN", "2017-10-13T07:10:00Z", 1507878600000L, 0, None))

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
      val expected = List(Arrival("4U", "ACL Forecast", "", "", "", "", "", "", 180, 149, 0, "", "", -904483842, "LHR", "S", "4U0460", "4U0460", "CGN", "2017-10-13T07:10:00Z", 1507878600000L, 0, None))

      arrivals === expected
    }
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the CrunchState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      ))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, Option(aclFlight))

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001"))

      crunch.liveTestProbe.fishForMessage(5 seconds) {
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
      val aclFlight = ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      val aclFlights = Flights(List(aclFlight))
      val liveFlight = ArrivalGenerator.apiFlight(flightId = 1, actPax = 20, schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z")
      val liveFlights = Flights(List(liveFlight))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, Option(aclFlights))
      offerAndWait(crunch.liveArrivalsInput, liveFlights)

      val expected = Set(liveFlight.copy(rawIATA = aclFlight.rawIATA, rawICAO = aclFlight.rawICAO))

      crunch.liveTestProbe.fishForMessage(5 seconds) {
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
        ArrivalGenerator.apiFlight(actPax = 150, schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast"),
        ArrivalGenerator.apiFlight(actPax = 151, schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")))
      val initialLive = Flights(Seq(
        ArrivalGenerator.apiFlight(actPax = 99, schDt = "2017-01-01T00:25Z", iata = "BA0003", status = "scheduled")))

      val newAcl = Set(
        ArrivalGenerator.apiFlight(actPax = 105, schDt = "2017-01-01T00:10Z", iata = "BA0011", status = "forecast"))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, Option(initialACL))
      offerAndWait(crunch.liveArrivalsInput, initialLive)

      Thread.sleep(1000) // Let the initial arrivals work their way through the system
      offerAndWait(crunch.baseArrivalsInput, Option(Flights(newAcl.toList)))

      val expected = initialLive.flights.toSet ++ newAcl

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          println(s"flights: $flightsResult")
          flightsResult == expected
      }

      true
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.apiFlight(actPax = 150, schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast")
      val initialAcl2 = ArrivalGenerator.apiFlight(actPax = 151, schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")
      val initialAcl = Flights(Seq(initialAcl1, initialAcl2))
      val initialLive = Flights(Seq(ArrivalGenerator.apiFlight(actPax = 99, schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "scheduled")))

      val newLive = Set(
        ArrivalGenerator.apiFlight(actPax = 105, schDt = "2017-01-01T00:05Z", estDt = "2017-01-01T00:06Z", iata = "BAW0001", status = "estimated"))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, Option(initialAcl))
      offerAndWait(crunch.liveArrivalsInput, initialLive)

      Thread.sleep(1000) // Let the initial arrivals work their way through the system

      offerAndWait(crunch.liveArrivalsInput, Flights(newLive.toList))

      val expected = newLive.map(_.copy(rawIATA = "BA0001")) + initialAcl2

      crunch.liveTestProbe.fishForMessage(5 seconds) {
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

      val initialAcl1 = ArrivalGenerator.apiFlight(actPax = 150, schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "forecast")
      val initialAcl2 = ArrivalGenerator.apiFlight(actPax = 151, schDt = "2017-01-01T00:15Z", iata = "BA0002", status = "forecast")
      val initialAcl = Flights(Seq(initialAcl1, initialAcl2))
      val initialLive = Flights(Seq(ArrivalGenerator.apiFlight(actPax = 99, schDt = "2017-01-01T00:05Z", iata = "BA0001", status = "scheduled")))

      val newAcl = Flights(Seq(
        ArrivalGenerator.apiFlight(actPax = 105, schDt = "2017-01-01T00:07Z", iata = "BA0001")))
      val newLive = Flights(Seq(
        ArrivalGenerator.apiFlight(actPax = 105, schDt = "2017-01-01T00:06Z", iata = "BAW0001")))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, Option(initialAcl))
      offerAndWait(crunch.liveArrivalsInput, initialLive)

      Thread.sleep(1000) // Let the initial arrivals work their way through the system

      offerAndWait(crunch.baseArrivalsInput, Option(newAcl))
      offerAndWait(crunch.liveArrivalsInput, newLive)

      val expected = newAcl.flights.toSet ++ newLive.flights.toSet ++ initialLive.flights.toSet

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      true
    }
  }

  "Looking at flights" >> {
    skipped("Integration test for ACL - requires SSL certificate to run")
    val ftpServer = ConfigFactory.load.getString("acl.host")
    val username = ConfigFactory.load.getString("acl.username")
    val path = ConfigFactory.load.getString("acl.keypath")

    val sftp: SFTPClient = sftpClient(ftpServer, username, path)
    val latestFile = latestFileForPort(sftp, "MAN")
    println(s"latestFile: $latestFile")
    val aclArrivals: List[Arrival] = arrivalsFromCsvContent(contentFromFileName(sftp, latestFile), regularTerminalMapping)

    val todayArrivals = aclArrivals
      .filter(_.SchDT < "2017-10-05T23:00")
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
