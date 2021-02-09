package controllers.application

import actors.GetState
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
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.deskrecs.{DynamicRunnableDeskRecs, OptimisationProviders, PortDesksAndWaitsProvider, RunnableOptimisation}
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.{Optimiser, SDate}
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSimulationUpload

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

trait WithSimulations {
  self: Application =>

  def simulationImport(): Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
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

            FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
              retrieveSimulationDesks(simulationParams, simulationConfig, date, fws, simulationParams.terminal)
            }.flatten

          case Failure(e) =>
            log.error("Invalid Simulation attempt", e)
            Future(BadRequest(e.getMessage))
        }
    }
  }

  def retrieveSimulationDesks(simulationParams: SimulationParams,
                              simulationConfig: AirportConfig,
                              date: SDateLike, fws: FlightsWithSplits,
                              terminal: Terminal): Future[Result] = {
    implicit val timeout: Timeout = new Timeout(2 minutes)
    val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))

    val splitsCalculator = SplitsCalculator(ctrl.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.splitAdjustments)

    val portDesksAndWaitsProvider: PortDesksAndWaitsProvider = PortDesksAndWaitsProvider(simulationConfig, Optimiser.crunch, PcpPax.bestPaxEstimateWithApi)
    val terminalDeskLimits = PortDeskLimits.fixed(simulationConfig)

    val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
      OptimisationProviders.arrivalsProvider(portStateActor),
      OptimisationProviders.liveManifestsProvider(ctrl.manifestsRouterActor),
      OptimisationProviders.historicManifestsProvider(airportConfig.portCode, ctrl.manifestLookupService),
      splitsCalculator,
      portDesksAndWaitsProvider.flightsToLoads,
      portDesksAndWaitsProvider.loadsToDesks,
      terminalDeskLimits)

    val (crunchRequestQueue, deskRecsKillSwitch) = RunnableOptimisation.createGraph(portStateActor, deskRecsProducer).run()

    crunchRequestQueue.offer(CrunchRequest(date.millisSinceEpoch, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch))

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == simulationParams.terminal))
    }

    futureDeskRecMinutes.map(deskRecMinutes => {
      deskRecsKillSwitch.shutdown()

      val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
        .minutes
        .map(dr => dr.key -> dr.toMinute).toMap

      val desks = StreamingDesksExport.crunchMinutesToRecsExportWithHeaders(
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
