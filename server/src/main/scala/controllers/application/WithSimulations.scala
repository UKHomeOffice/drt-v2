package controllers.application

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.queues.FlightsRouterActor
import akka.NotUsed
import akka.actor.Props
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import controllers.Application
import controllers.application.exports.CsvFileStreaming
import controllers.application.exports.CsvFileStreaming.csvFileResult
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinutes}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import manifests.queues.SplitsCalculator
import play.api.mvc._
import services.SDate
import services.crunch.deskrecs.OptimisationProviders
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios.simulationResult
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSimulationUpload
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

trait WithSimulations {
  self: Application =>

  def simulationExport(): Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    Action(parse.defaultBodyParser).async {
      request =>
        implicit val timeout: Timeout = new Timeout(2 minutes)

        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val date = SDate(simulationParams.date)
            val eventualFlightsWithSplitsStream: Future[Source[FlightsWithSplits, NotUsed]] = (ctrl.portStateActor ? GetFlightsForTerminalDateRange(
              date.getLocalLastMidnight.millisSinceEpoch,
              date.getLocalNextMidnight.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[FlightsWithSplits, NotUsed]]

            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
              val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              simulationResult(
                simulationParams,
                simulationConfig,
                SplitsCalculator(ctrl.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.splitAdjustments),
                OptimisationProviders.arrivalsProvider(portStateActor),
                OptimisationProviders.liveManifestsProvider(ctrl.manifestsRouterActor),
                OptimisationProviders.historicManifestsProvider(airportConfig.portCode,
                  ctrl.manifestLookupService),
                ctrl.flightsActor,
                portStateActor
              )
            }.flatten

            simulationResultAsCsv(simulationParams, simulationParams.terminal, futureDeskRecs)
          case Failure(e) =>
            log.error("Invalid Simulation attempt", e)
            Future(BadRequest(e.getMessage))
        }
    }
  }

  def simulation(): Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    Action(parse.defaultBodyParser).async {
      request =>
        implicit val timeout: Timeout = new Timeout(2 minutes)


        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val date = SDate(simulationParams.date)
            val eventualFlightsWithSplitsStream: Future[Source[FlightsWithSplits, NotUsed]] = (ctrl.portStateActor ? GetFlightsForTerminalDateRange(
              date.getLocalLastMidnight.millisSinceEpoch,
              date.getLocalNextMidnight.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[FlightsWithSplits, NotUsed]]


            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws => {
              val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              simulationResult(
                simulationParams,
                simulationConfig,
                SplitsCalculator(ctrl.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.splitAdjustments),
                OptimisationProviders.arrivalsProvider(portStateActor),
                OptimisationProviders.liveManifestsProvider(ctrl.manifestsRouterActor),
                OptimisationProviders.historicManifestsProvider(airportConfig.portCode, ctrl.manifestLookupService),
                ctrl.flightsActor,
                portStateActor
              )
            }
            }.flatten

            futureDeskRecs.map(res => {
              Ok(write(SimulationResult(simulationParams, summary(res, simulationParams.terminal))))
            })
          case Failure(e) =>
            log.error("Invalid Simulation attempt", e)
            Future(BadRequest(e.getMessage))
        }
    }
  }

  def summary(mins: DeskRecMinutes, terminal: Terminal): Map[Queues.Queue, List[CrunchApi.CrunchMinute]] = {
    val ps = PortState(List(), mins.minutes.map(_.toMinute), List())
    val start = mins.minutes.map(_.minute).min
    val queues = mins.minutes.map(_.queue).toSet.toList

    ps
      .crunchSummary(SDate(start), MilliTimes.fifteenMinuteSlotsInDay, 15, terminal, queues)
      .values
      .flatten
      .toList
      .collect {
        case (_, cm) => cm
      }
      .groupBy(_.queue)
      .mapValues(_.sortBy(_.minute))

  }


  def simulationResultAsCsv(simulationParams: SimulationParams,
                            terminal: Terminal,
                            futureDeskRecMinutes: Future[DeskRecMinutes]): Future[Result] = {
    implicit val timeout: Timeout = new Timeout(2 minutes)

    val date = SDate(simulationParams.date)

    futureDeskRecMinutes.map(deskRecMinutes => {
      val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
        .minutes
        .map(dr => dr.key -> dr.toMinute).toMap

      val desks: String = StreamingDesksExport.crunchMinutesToRecsExportWithHeaders(
        terminal,
        airportConfig.desksExportQueueOrder,
        date.toLocalDate,
        crunchMinutes.map {
          case (_, cm) => cm
        }
      )

      csvFileResult(
        CsvFileStreaming.makeFileName(s"simulation-${simulationParams.passengerWeighting}",
          simulationParams.terminal,
          date,
          date,
          airportConfig.portCode
        ),
        desks
      )
    })
  }
}
