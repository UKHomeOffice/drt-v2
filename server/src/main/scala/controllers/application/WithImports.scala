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
import drt.auth.{ArrivalSimulationUpload, PortFeedUpload}
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.airportconfig.Lhr
import drt.shared.api.Arrival
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, MultipartFormData, Request, Result}
import server.feeds.StoreFeedImportArrivals
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeskRecs}
import services.exports.Exports
import services.exports.summaries.queues.TerminalQueuesSummary
import services.imports.{ArrivalCrunchSimulationActor, ArrivalImporter}
import services.{Optimiser, SDate}

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
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

  def simulationImport(): Action[MultipartFormData[Files.TemporaryFile]] = authByRole(ArrivalSimulationUpload) { Action.async(parse.multipartFormData) {
    request: Request[MultipartFormData[Files.TemporaryFile]] =>
      implicit val timeout = new Timeout(2 minutes)
      val filePath = s"/tmp/${UUID.randomUUID().toString}"

      val eventualResult: Future[Result] = request.body.file("arrivals-file") match {
        case Some(arrivalsFile) =>

          val passengerWeighting = request.body.dataParts.get("passenger-weighting")
            .flatMap(_.headOption)
            .getOrElse("1.0").toDouble

          val (terminal: Terminal, date: SDateLike) = arrivalsFileToTerminalAndDate(arrivalsFile)

          val flightsWithSplits: Array[ApiFlightWithSplits] = flightsWithSplitsFromPost(arrivalsFile, terminal, passengerWeighting)

          val lhrHalved = Lhr.config.copy(
            minMaxDesksByTerminalQueue24Hrs = Lhr.config.minMaxDesksByTerminalQueue24Hrs.mapValues(_.map {
              case (q, (_, max)) =>
                val openDesks = max.map(x => x / 2)
                q -> (openDesks, openDesks)
            }),
            eGateBankSize = 5,
            slaByQueue = Lhr.config.slaByQueue.mapValues(_ => 15),
            crunchOffsetMinutes = 0
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

        case None => Future(BadRequest(""))
      }

      eventualResult
  }}

  def flightsWithSplitsFromPost(arrivalsFile: MultipartFormData.FilePart[Files.TemporaryFile],
                                terminal: Terminal,
                                passengerWeighting: Double
                               ): Array[ApiFlightWithSplits] = {
    val bufferedSource: BufferedSource = scala.io.Source.fromFile(arrivalsFile.ref.path.toUri)(Codec.UTF8)
    val csv = bufferedSource.getLines().filter(_.contains(",")).mkString("\n")
    bufferedSource.close()

    val flights = ArrivalImporter(csv, terminal)
      .map(fws =>
        fws.copy(
          apiFlight = applyWeighting(fws.apiFlight, passengerWeighting)
        )
      )

    flights.filter(f => f.splits.flatMap(_.splits.map(_.paxCount)).sum > 0)
  }

  def applyWeighting(arrival: Arrival, passengerWeighting: Double): Arrival = {
    val arrival1 = arrival.copy(
      ActPax = arrival.ActPax.map(p => {
        p * passengerWeighting
      }.toInt),
      TranPax = arrival.TranPax.map(p => {
        p * passengerWeighting
      }.toInt)
    )
    arrival1
  }

  def arrivalsFileToTerminalAndDate(arrivalsFile: MultipartFormData.FilePart[Files.TemporaryFile]): (Terminal, SDateLike) = {
    val fileNameRegex = "[A-Z]{3}-([^-]{1,2})-flights-(.{10}).*".r
    val (t, d) = arrivalsFile.filename match {
      case fileNameRegex(t, d) => (t, d)
    }
    (Terminal(t), SDate(d))
  }
}
