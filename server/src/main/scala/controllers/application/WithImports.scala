package controllers.application

import java.nio.file.Paths
import java.util.UUID

import actors.GetState
import akka.actor.Props
import akka.pattern.ask
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Timeout
import api.ApiResponseBody
import controllers.Application
import drt.auth.PortFeedUpload
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.Terminals.Terminal
import drt.shared.airportconfig.Lhr
import drt.shared.{MilliTimes, PcpPax, TQM}
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, Request}
import server.feeds.StoreFeedImportArrivals
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeskRecs}
import services.exports.Exports
import services.exports.summaries.queues.TerminalQueuesSummary
import services.imports.{ArrivalCrunchSimulationActor, ArrivalImporter}
import services.{Optimiser, SDate}
import scala.concurrent.duration._
import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.io.{BufferedSource, Codec}


trait WithImports {
  self: Application =>

  def feedImport(feedType: String, portCode: String): Action[Files.TemporaryFile] = authByRole(PortFeedUpload) {
    Action.async(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
      val filePath = s"/tmp/${UUID.randomUUID().toString}"

      request.body.moveTo(Paths.get(filePath), replace = true)

      val extractedArrivals = LHRForecastCSVExtractor(filePath)

      val response = if (extractedArrivals.nonEmpty) {
        log.info(s"Import found ${extractedArrivals.length} arrivals")
        ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(extractedArrivals))
        Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
      } else BadRequest(toJson(ApiResponseBody("No arrivals found")))

      Future(response)
    }
  }

  def simulationImport(): Action[Files.TemporaryFile] = {
    Action.async(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>

      implicit val timeout = new Timeout(2 minutes)
      val filePath = s"/tmp/${UUID.randomUUID().toString}"

      request.body.moveTo(Paths.get(filePath), replace = true)

      val terminal = Terminal("T5")

      val bufferedSource: BufferedSource = scala.io.Source.fromFile(filePath)(Codec.UTF8)
      val csv = bufferedSource.getLines().filter(_.contains(",")).mkString("\n")
      bufferedSource.close()

      val date = SDate("2020-06-17T05:30:00Z")

      val flights = ArrivalImporter(csv, terminal)
      val flightsWithSplits = flights.filter(f => f.splits.flatMap(_.splits.map(_.paxCount)).sum > 0)

      val lhrHalved = Lhr.config.copy(
        minMaxDesksByTerminalQueue24Hrs = Lhr.config.minMaxDesksByTerminalQueue24Hrs.mapValues(_.map {
          case (q, (_, max)) =>
            val openDesks = max.map(x => x / 2)
            q -> (openDesks, openDesks)
        }),
        eGateBankSize = 5,
        slaByQueue = Lhr.config.slaByQueue.mapValues(_ => 15)
      )
      val fws = FlightsWithSplits(flightsWithSplits.map(f => f.unique -> f).toMap)

      val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(fws)))

      val dawp = DesksAndWaitsPortProvider(lhrHalved, Optimiser.crunch, PcpPax.bestPaxEstimateWithApi)
      val (runnableDeskRecs, _): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = RunnableDeskRecs(portStateActor, dawp, PortDeskLimits.fixed(lhrHalved)).run()
      runnableDeskRecs.offer(date.millisSinceEpoch)

      val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
        case drm: DeskRecMinutes => drm
      }

      val queues = lhrHalved.queuesByTerminal(terminal)
      val minutes = date.getLocalLastMidnight.millisSinceEpoch to date.getLocalNextMidnight.millisSinceEpoch by 15 * MilliTimes.oneMinuteMillis

      futureDeskRecMinutes.map(deskRecMinutes => {

        val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
          .minutes
          .map(dr => dr.key -> dr.toMinute).toMap

        val desks = TerminalQueuesSummary(queues, Exports.queueSummaries(queues, 15, minutes, crunchMinutes, SortedMap())).toCsvWithHeader

        Exports.csvFileResult("desks.csv", desks)
      })

    }
  }
}
