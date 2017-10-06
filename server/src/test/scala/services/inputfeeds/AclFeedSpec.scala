package services.inputfeeds

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import net.schmizz.sshj.sftp.SFTPClient
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import server.feeds.acl.AclFeed.{arrivalsFromCsvContent, contentFromFileName, latestFileForPort, sftpClient}
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch._

import scala.collection.immutable.List
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  "ACL feed parsing" >> {
    "Given ACL csv content containing a header line and one arrival line " +
      "When I ask for the arrivals " +
      "Then I should see a list containing the appropriate Arrival" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

      val

      arrivals = arrivalsFromCsvContent(csvContent)
      val expected = List(Arrival("4U", "Forecast", "", "", "", "", "", "", 180, 149, 0, "", "", -904483842, "LHR", "T2", "4U0460", "4U0460", "CGN", "2017-10-13T07:10:00Z", 1507878600000L, 0, None))

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one departure line " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent)
      val expected = List()

      arrivals === expected
    }
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the CrunchState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(aclFlight), Source(List()), Source(List()))
      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])
      val flightsResult = result.flights.map(_.apiFlight)

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001"))

      flightsResult === expected
    }

    "Given some initial arrivals in the Arrivals Stage and no ACL or live arrivals " +
      "Then I should not see any CrunchState messages" >> {
      val scheduledForecast = "2017-01-01T00:05Z"
      val scheduledLive = "2017-01-01T00:00Z"
      val estimatedLive = "2017-01-01T00:00Z"
      val actChoxLive = "2017-01-01T00:30Z"

      val forecastArrival = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = scheduledForecast, iata = "BA0001", status = "forecast")
      val liveArrival = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = scheduledLive, iata = "BA0002", estDt = estimatedLive, status = "estimated")
      val originalArrivals = Set(forecastArrival, liveArrival)

      val updatedLive = ArrivalGenerator.apiFlight(flightId = 1, actPax = 101, schDt = scheduledLive, iata = "BAW001", estDt = estimatedLive, actChoxDt = actChoxLive, status = "onchox")
      val liveFlights = List(Flights(List(updatedLive)))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)
      val testProbe = TestProbe()

      val runnableGraphDispatcher =
        runCrunchGraph[SourceQueueWithComplete[Flights], SourceQueueWithComplete[VoyageManifests]](
          optionalInitialFlights = Option(FlightsWithSplits(originalArrivals.map(a => ApiFlightWithSplits(a, Set())).toList)),
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduledLive)).millisSinceEpoch
        ) _
      val baseInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val liveInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val manifestsInput = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)
      runnableGraphDispatcher(baseInput, liveInput, manifestsInput)

      testProbe.expectNoMsg(1 seconds)

      true
    }

    "Given an ACL feed with one flight and the same flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the one flight in the CrunchState with the ACL flightcode and live chox" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      )))
      val liveFlight = ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z")
      val liveFlights = Flights(List(liveFlight))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(aclFlight), Source(List(liveFlights)), Source(List()))
      testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])
      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])
      val flightsResult = result.flights.map(_.apiFlight)

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001", actChoxDt = "2017-01-01T00:30Z"))

      flightsResult === expected
    }

    "Given some initial arrivals, one ACL arrival and no live arrivals " +
      "When I ask for a crunch " +
      "Then I should only see the one ACL arrival" >> {
      val scheduledForecast = "2017-01-01T00:05Z"
      val scheduledLive = "2017-01-01T00:00Z"
      val estimatedLive = "2017-01-01T00:00Z"
      val actChoxLive = "2017-01-01T00:30Z"

      val forecastArrival = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = scheduledForecast, iata = "BA0001", status = "forecast")
      val liveArrival = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = scheduledLive, iata = "BA0002", estDt = estimatedLive, status = "estimated")
      val initialArrivals = FlightsWithSplits(List(ApiFlightWithSplits(forecastArrival, Set()), ApiFlightWithSplits(liveArrival, Set())))

      val baseFlights = Flights(List(forecastArrival))

      val testProbe = TestProbe()

      val runnableGraphDispatcher =
        runCrunchGraph[SourceQueueWithComplete[Flights], SourceQueueWithComplete[VoyageManifests]](
          optionalInitialFlights = Option(initialArrivals),
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduledLive)).millisSinceEpoch
        ) _
      val baseInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val liveInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val manifestsInput = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)
      val (bf, _, _, _, _) = runnableGraphDispatcher(baseInput, liveInput, manifestsInput)

      bf.offer(baseFlights)

      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)
      val expected = Set(forecastArrival, liveArrival)

      flightsResult === expected
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledForecast = "2017-01-01T00:05Z"
      val scheduledLive = "2017-01-01T00:00Z"
      val estimatedLive = "2017-01-01T00:00Z"
      val actChoxLive = "2017-01-01T00:30Z"

      val forecastArrival = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = scheduledForecast, iata = "BA0001", status = "forecast")
      val liveArrival = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = scheduledLive, iata = "BA0002", estDt = estimatedLive, status = "estimated")
      val initialArrivals = FlightsWithSplits(List(ApiFlightWithSplits(forecastArrival, Set()), ApiFlightWithSplits(liveArrival, Set())))

      val updatedLive = liveArrival.copy(ActChoxDT = actChoxLive, Status = "onchox")
      val liveFlights = Flights(List(updatedLive))

      val testProbe = TestProbe()

      val runnableGraphDispatcher =
        runCrunchGraph[SourceQueueWithComplete[Flights], SourceQueueWithComplete[VoyageManifests]](
          optionalInitialFlights = Option(initialArrivals),
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduledLive)).millisSinceEpoch
        ) _
      val baseInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val liveInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val manifestsInput = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)
      val (_, lf, _, _, _) = runnableGraphDispatcher(baseInput, liveInput, manifestsInput)

      lf.offer(liveFlights)

      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)
      val expected = Set(forecastArrival, updatedLive)

      flightsResult === expected
    }

    "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the ACL & live arrivals, not the initial arrivals" >> {
      val initialScheduledForecast = "2017-01-01T00:05Z"
      val initialScheduledLive = "2017-01-01T00:00Z"

      val newScheduledForecast = "2017-01-01T00:15Z"
      val newScheduledLive = "2017-01-01T00:10Z"

      val initialForecastArrival = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = initialScheduledForecast, iata = "BA0001", status = "forecast")
      val initialLiveArrival = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = initialScheduledLive, iata = "BA0002", status = "scheduled")
      val initialArrivals = FlightsWithSplits(List(ApiFlightWithSplits(initialForecastArrival, Set()), ApiFlightWithSplits(initialLiveArrival, Set())))

      val newForecast = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = newScheduledForecast, iata = "BA0001", status = "forecast")
      val forecastFlights = Flights(List(newForecast))

      val newLive = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = newScheduledLive, iata = "BA0002", status = "estimated")
      val liveFlights = Flights(List(newLive))

      val testProbe = TestProbe()

      val runnableGraphDispatcher =
        runCrunchGraph[SourceQueueWithComplete[Flights], SourceQueueWithComplete[VoyageManifests]](
          optionalInitialFlights = Option(initialArrivals),
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(initialScheduledLive)).millisSinceEpoch
        ) _
      val baseInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val liveInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val manifestsInput = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)
      val (bf, lf, _, _, _) = runnableGraphDispatcher(baseInput, liveInput, manifestsInput)

      bf.offer(forecastFlights)
      testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      lf.offer(liveFlights)
      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)
      val expected = Set(newForecast, newLive)

      flightsResult === expected
    }

    "Given one live arrival followed by one ACL arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the ACL & live arrivals, not the initial arrivals" >> {
      val initialScheduledForecast = "2017-01-01T00:05Z"
      val initialScheduledLive = "2017-01-01T00:00Z"

      val newScheduledForecast = "2017-01-01T00:15Z"
      val newScheduledLive = "2017-01-01T00:10Z"

      val initialForecastArrival = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = initialScheduledForecast, iata = "BA0001", status = "forecast")
      val initialLiveArrival = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = initialScheduledLive, iata = "BA0002", status = "scheduled")
      val initialArrivals = FlightsWithSplits(List(ApiFlightWithSplits(initialForecastArrival, Set()), ApiFlightWithSplits(initialLiveArrival, Set())))

      val newForecast = ArrivalGenerator.apiFlight(flightId = 1, actPax = 150, schDt = newScheduledForecast, iata = "BA0001", status = "forecast")
      val forecastFlights = Flights(List(newForecast))

      val newLive = ArrivalGenerator.apiFlight(flightId = 2, actPax = 99, schDt = newScheduledLive, iata = "BA0002", status = "estimated")
      val liveFlights = Flights(List(newLive))

      val testProbe = TestProbe()

      val runnableGraphDispatcher =
        runCrunchGraph[SourceQueueWithComplete[Flights], SourceQueueWithComplete[VoyageManifests]](
          optionalInitialFlights = Option(initialArrivals),
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(initialScheduledLive)).millisSinceEpoch
        ) _
      val baseInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val liveInput = Source.queue[Flights](1, OverflowStrategy.backpressure)
      val manifestsInput = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)
      val (bf, lf, _, _, _) = runnableGraphDispatcher(baseInput, liveInput, manifestsInput)

      lf.offer(liveFlights)
      testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      bf.offer(forecastFlights)
      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)
      val expected = Set(newForecast, newLive)

      flightsResult === expected
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
    val aclArrivals: List[Arrival] = arrivalsFromCsvContent(contentFromFileName(sftp, latestFile))

    val todayArrivals = aclArrivals
      .filter(_.SchDT < "2017-10-05T23:00")
      .groupBy(_.Terminal)

    todayArrivals.foreach {
      case (tn, arrivals) =>
        val tByUniqueId = todayArrivals(tn).groupBy(_.uniqueId)
        println(s"uniques for $tn: ${tByUniqueId.keys.size} flights")
        val nonUniques = tByUniqueId.filter {
          case (uid, a) => a.length > 1
        }.foreach {
          case (uid, a) => println(s"non-unique: $uid -> $a")
        }
    }

    todayArrivals.keys.foreach(t => println(s"terminal $t has ${todayArrivals(t).size} flights"))

    true
  }

}
