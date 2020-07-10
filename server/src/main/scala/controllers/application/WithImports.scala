package controllers.application

import java.nio.file.Paths
import java.util.UUID

import actors.{GetFlightsForTerminal, GetState}
import akka.actor.Props
import akka.pattern.ask
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Timeout
import api.ApiResponseBody
import controllers.Application
import controllers.application.exports.CsvFileStreaming
import drt.auth.{ArrivalSimulationUpload, PortFeedUpload}
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, AnyContent, AnyContentAsFormUrlEncoded, MultipartFormData, Request}
import server.feeds.StoreFeedImportArrivals
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeskRecs}
import services.exports.Exports
import services.exports.summaries.queues.TerminalQueuesSummary
import services.imports.{ArrivalCrunchSimulationActor, ArrivalImporter}
import services.{SDate, TryRenjin}

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

  def simulationImport(): Action[Map[String, Seq[String]]] = authByRole(ArrivalSimulationUpload) {
    Action(parse.formUrlEncoded).async {
      request: Request[Map[String, Seq[String]]] =>
        implicit val timeout: Timeout = new Timeout(2 minutes)

        val passengerWeighting = fieldFromRequest(request, "passenger-weighting")
          .getOrElse("1.0").toDouble

        val terminalString = fieldFromRequest(request, "terminal")
          .getOrElse(airportConfig.terminals.head.toString)

        val terminal = Terminal(terminalString)

        val processingTimes: Map[PaxTypeAndQueue, Double] = airportConfig.terminalProcessingTimes.head._2.map {
          case (ptq, defaultValue) =>
            ptq -> request.body.get(ptq.key).flatMap(_.headOption)
              .map(s => s.toDouble / 60)
              .getOrElse(defaultValue)
        }

        val openDesks: Map[Queues.Queue, (List[Int], List[Int])] = airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
          case (q, (origMinDesks, origMaxDesks)) =>
            val maxDesks: Option[Int] = request.body.get(s"${q}_max").flatMap(_.headOption).map(_.toInt)
            val minDesks: Option[Int] = request.body.get(s"${q}_min").flatMap(_.headOption).map(_.toInt)
            val newMaxDesks = origMaxDesks.map(d => maxDesks.getOrElse(d))
            val newMinDesks = origMinDesks.map(d => minDesks.getOrElse(d))
            q -> (newMinDesks, newMaxDesks)
        }

        val simulationConfig = airportConfig.copy(
          minMaxDesksByTerminalQueue24Hrs = airportConfig.minMaxDesksByTerminalQueue24Hrs + (terminal -> openDesks),
          slaByQueue = airportConfig.slaByQueue.mapValues(_ => 15),
          crunchOffsetMinutes = 0,
          terminalProcessingTimes = airportConfig.terminalProcessingTimes + (terminal -> processingTimes)
        )

        val date = fieldFromRequest(request, "simulation-date").map(d => SDate(d))
          .getOrElse(SDate.now())

        val eventualFlightsWithSplits: Future[FlightsWithSplits] = (ctrl.portStateActor ? GetFlightsForTerminal(
          date.getLocalLastMidnight.millisSinceEpoch,
          date.getLocalNextMidnight.millisSinceEpoch,
          terminal
        )).mapTo[FlightsWithSplits]


        eventualFlightsWithSplits.map { fws =>
          val weightedFlights = FlightsWithSplits(fws.flights.map {
            case (ua, f) => (ua, f.copy(apiFlight = applyWeighting(f.apiFlight, passengerWeighting)))
          })

          val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(weightedFlights)))

          val dawp = DesksAndWaitsPortProvider(simulationConfig, TryRenjin.crunch, PcpPax.bestPaxEstimateWithApi)
          val (runnableDeskRecs, _): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = RunnableDeskRecs(portStateActor, dawp, PortDeskLimits.fixed(simulationConfig)).run()
          runnableDeskRecs.offer(date.millisSinceEpoch)

          val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
            case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == terminal))
          }

          val queues = simulationConfig.nonTransferQueues(terminal)
          val minutes = date.getLocalLastMidnight.millisSinceEpoch to date.getLocalNextMidnight.millisSinceEpoch by 15 * MilliTimes.oneMinuteMillis

          futureDeskRecMinutes.map(deskRecMinutes => {

            val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
              .minutes
              .map(dr => dr.key -> dr.toMinute).toMap

            val desks = TerminalQueuesSummary(queues, Exports.queueSummaries(queues, 15, minutes, crunchMinutes, SortedMap())).toCsvWithHeader

            Exports.csvFileResult(
              CsvFileStreaming.makeFileName(s"simulation-$passengerWeighting",
                terminal,
                date,
                date,
                airportConfig.portCode
              ),
              desks
            )
          })
        }.flatten
    }
  }

  private def fieldFromRequest(request: Request[Map[String, Seq[String]]], name: String) = {
    request.body.get(name)
      .flatMap(_.headOption)
  }

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
