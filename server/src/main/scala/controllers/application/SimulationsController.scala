package controllers.application

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.Props
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import drt.shared.CrunchApi._
import drt.shared._
import manifests.queues.SplitsCalculator
import play.api.mvc._
import services.crunch.deskrecs.OptimisationProviders
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSimulationUpload
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, MilliTimes, SDate, UtcDate}
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class SimulationsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def simulationExport: Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    implicit val timeout: Timeout = new Timeout(10 minutes)

    Action(parse.defaultBodyParser).async {
      request =>
        SimulationParams
          .fromQueryStringParams(request.queryString, ctrl.paxFeedSourceOrder) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)
            val date = SDate(simulationParams.date)
            val start = date.getLocalLastMidnight
            val end = date.getLocalNextMidnight
            val eventualFlightsWithSplitsStream = (ctrl.actorService.portStateActor ? GetFlightsForTerminalDateRange(
              start.millisSinceEpoch,
              end.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]

            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
              val portStateActor = actorSystem.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              Scenarios.simulationResult(
                simulationParams = simulationParams,
                simulationAirportConfig = simulationConfig,
                sla = (_: LocalDate, queue: Queue) => Future.successful(simulationParams.slaByQueue(queue)),
                splitsCalculator = SplitsCalculator(ctrl.applicationService.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.queueAdjustments),
                flightsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
                portStateActor = portStateActor,
                redListUpdatesProvider = () => ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
                egateBanksProvider = () => ctrl.applicationService.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates],
                paxFeedSourceOrder = ctrl.feedService.paxFeedSourceOrder,
                deskLimitsProviders = ctrl.applicationService.deskLimitsProviders,
              )
            }.flatten

            simulationResultAsCsv(simulationParams, simulationParams.terminal, futureDeskRecs)
          case Failure(e) =>
            log.error(s"Invalid Simulation attempt: ${e.getMessage}")
            Future(BadRequest("Unable to parse parameters: " + e.getMessage))
        }
    }
  }

  def simulation: Action[AnyContent] = authByRole(ArrivalSimulationUpload) {
    Action(parse.defaultBodyParser).async {
      request =>
        implicit val timeout: Timeout = new Timeout(10 minutes)

        SimulationParams
          .fromQueryStringParams(request.queryString, ctrl.paxFeedSourceOrder) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val date = SDate(simulationParams.date)
            val eventualFlightsWithSplitsStream: Future[Source[(UtcDate, FlightsWithSplits), NotUsed]] = (ctrl.actorService.portStateActor ? GetFlightsForTerminalDateRange(
              date.getLocalLastMidnight.millisSinceEpoch,
              date.getLocalNextMidnight.millisSinceEpoch,
              simulationParams.terminal
            )).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]

            val futureDeskRecs: Future[DeskRecMinutes] = FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
              val portStateActor = actorSystem.actorOf(Props(new ArrivalCrunchSimulationActor(simulationParams.applyPassengerWeighting(fws))))
              val fwsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor)
              Scenarios.simulationResult(
                simulationParams = simulationParams,
                simulationAirportConfig = simulationConfig,
                sla = (_: LocalDate, queue: Queue) => Future.successful(simulationParams.slaByQueue(queue)),
                splitsCalculator = SplitsCalculator(ctrl.applicationService.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.queueAdjustments),
                flightsProvider = fwsProvider,
                portStateActor = portStateActor,
                redListUpdatesProvider = () => ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
                egateBanksProvider = () => ctrl.applicationService.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates],
                paxFeedSourceOrder = ctrl.feedService.paxFeedSourceOrder,
                deskLimitsProviders = ctrl.applicationService.deskLimitsProviders
              )
            }.flatten

            futureDeskRecs.map(res => {
              Ok(write(SimulationResult(simulationParams, summary(res, simulationParams.terminal))))
            })
          case Failure(e) =>
            log.error(s"Invalid Simulation attempt: ${e.getMessage}")
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
      .view.mapValues(_.sortBy(_.minute)).toMap

  }

  private def simulationResultAsCsv(simulationParams: SimulationParams,
                                    terminal: Terminal,
                                    futureDeskRecMinutes: Future[DeskRecMinutes]): Future[Result] = {

    val date = SDate(simulationParams.date)

    futureDeskRecMinutes.map(deskRecMinutes => {
      val crunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ deskRecMinutes
        .minutes
        .map(dr => dr.key -> dr.toMinute).toMap

      val fileName = CsvFileStreaming.makeFileName(s"simulation-${simulationParams.passengerWeighting}",
        Option(simulationParams.terminal),
        simulationParams.date,
        simulationParams.date,
        airportConfig.portCode
      ) + ".csv"

      val stream = StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
        date.getLocalLastMidnight,
        date.getLocalNextMidnight,
        terminal,
        airportConfig.desksExportQueueOrder,
        (_, _) => Future.successful(Option(MinutesContainer(crunchMinutes.values.toSeq))),
        (_, _) => Future.successful(None),
        None,
        15,
      )

      val result: Result = Try(sourceToCsvResponse(stream, fileName)) match {
        case Success(value) => value
        case Failure(t) =>
          log.error(s"Failed to get CSV export: ${t.getMessage}")
          BadRequest("Failed to get CSV export")
      }

      result
    })
  }
}
