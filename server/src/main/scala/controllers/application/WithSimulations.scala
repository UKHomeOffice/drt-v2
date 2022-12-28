package controllers.application

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.RouteHistoricManifestActor
import actors.persistent.staffing.GetState
import actors.routing.FlightsRouterActor
import actors.routing.minutes.MinutesActorLike.MinutesLookup
import akka.NotUsed
import akka.actor.Props
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import controllers.Application
import controllers.application.exports.CsvFileStreaming
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import manifests.queues.SplitsCalculator
import play.api.mvc._
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.deskrecs.OptimisationProviders
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios.simulationResult
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSimulationUpload
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{MilliTimes, UtcDate}
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait WithSimulations {
  self: Application =>

  def simulationExport(): Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    Action(parse.defaultBodyParser).async {
      request =>
        implicit val timeout: Timeout = new Timeout(10 minutes)

        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val date = SDate(simulationParams.date)
            val start = date.getLocalLastMidnight
            val end = date.getLocalNextMidnight
            val eventualFlightsWithSplitsStream: Future[Source[(UtcDate, FlightsWithSplits), NotUsed]] = (ctrl.portStateActor ? GetFlightsForTerminalDateRange(
              start.millisSinceEpoch,
              end.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]

            val manifestCacheLookup = RouteHistoricManifestActor.manifestCacheLookup(airportConfig.portCode, now, system, timeout, ec)

            val manifestCacheStore = RouteHistoricManifestActor.manifestCacheStore(airportConfig.portCode, now, system, timeout, ec)

            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
              val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              simulationResult(
                simulationParams,
                simulationConfig,
                SplitsCalculator(ctrl.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.splitAdjustments),
                OptimisationProviders.flightsWithSplitsProvider(portStateActor),
                OptimisationProviders.liveManifestsProvider(ctrl.manifestsRouterActor),
                OptimisationProviders.historicManifestsProvider(airportConfig.portCode, ctrl.manifestLookupService, manifestCacheLookup, manifestCacheStore),
                OptimisationProviders.historicManifestsPaxProvider(airportConfig.portCode, ctrl.manifestLookupService),
                ctrl.flightsRouterActor,
                portStateActor,
                () => ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
                () => ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates],
              )
            }.flatten

            simulationResultAsCsv(simulationParams, simulationParams.terminal, futureDeskRecs)
          case Failure(e) =>
            log.error("Invalid Simulation attempt", e)
            Future(BadRequest("Unable to parse parameters: " + e.getMessage))
        }
    }
  }

  def simulation(): Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    Action(parse.defaultBodyParser).async {
      request =>
        implicit val timeout: Timeout = new Timeout(10 minutes)


        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val date = SDate(simulationParams.date)
            val eventualFlightsWithSplitsStream: Future[Source[(UtcDate, FlightsWithSplits), NotUsed]] = (ctrl.portStateActor ? GetFlightsForTerminalDateRange(
              date.getLocalLastMidnight.millisSinceEpoch,
              date.getLocalNextMidnight.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]

            val manifestCacheLookup = RouteHistoricManifestActor.manifestCacheLookup(airportConfig.portCode, now, system, timeout, ec)

            val manifestCacheStore = RouteHistoricManifestActor.manifestCacheStore(airportConfig.portCode, now, system, timeout, ec)

            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws => {
              val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              simulationResult(
                simulationParams = simulationParams,
                simulationAirportConfig = simulationConfig,
                splitsCalculator = SplitsCalculator(ctrl.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.splitAdjustments),
                flightsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
                liveManifestsProvider = OptimisationProviders.liveManifestsProvider(ctrl.manifestsRouterActor),
                historicManifestsProvider = OptimisationProviders.historicManifestsProvider(airportConfig.portCode, ctrl.manifestLookupService, manifestCacheLookup, manifestCacheStore),
                historicManifestsPaxProvider = OptimisationProviders.historicManifestsPaxProvider(airportConfig.portCode, ctrl.manifestLookupService),
                flightsActor = ctrl.flightsRouterActor,
                portStateActor = portStateActor,
                redListUpdatesProvider = () => ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
                egateBanksProvider = () => ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates],
              )
            }
            }.flatten

            futureDeskRecs.map(res => {
              Ok(write(SimulationResult(simulationParams, summary(res, simulationParams.terminal))))
            })
          case Failure(e) =>
            log.error("Invalid Simulation attempt", e)
            Future(BadRequest("Unable to parse parameters: " + e.getMessage))
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

    val date = SDate(simulationParams.date)

    futureDeskRecMinutes.map(deskRecMinutes => {
      val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
        .minutes
        .map(dr => dr.key -> dr.toMinute).toMap

      val fileName = CsvFileStreaming.makeFileName(s"simulation-${simulationParams.passengerWeighting}",
        simulationParams.terminal,
        simulationParams.date,
        simulationParams.date,
        airportConfig.portCode
      )

      val stream = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        date.getLocalLastMidnight,
        date.getLocalNextMidnight,
        terminal,
        airportConfig.desksExportQueueOrder,
        simulationQueuesLookup(crunchMinutes),
        staffLookupNoop,
        None
      )

      Try(sourceToCsvResponse(stream, fileName)) match {
        case Success(value) => value
        case Failure(t) =>
          log.error("Failed to get CSV export", t)
          BadRequest("Failed to get CSV export")
      }

    })
  }

  def simulationQueuesLookup(cms: SortedMap[TQM, CrunchMinute])(
    terminalDate: (Terminal, UtcDate),
    maybePit: Option[MillisSinceEpoch]
  ): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = Future(Option(MinutesContainer(cms.values.toSeq)))

  def staffLookupNoop: MinutesLookup[StaffMinute, TM] = (terminalDate: (Terminal, UtcDate), maybePit: Option[MillisSinceEpoch]) => Future(None)
}
