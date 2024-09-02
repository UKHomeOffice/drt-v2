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
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.OptimisationProviders
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSimulationUpload
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
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
    Action(parse.defaultBodyParser).async {
      request =>
        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)

            val futureDeskRecs = deskRecsForSimulation(simulationParams, simulationConfig)

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
        SimulationParams
          .fromQueryStringParams(request.queryString) match {
          case Success(simulationParams) =>
            val simulationConfig = simulationParams.applyToAirportConfig(airportConfig)
            val futureDeskRecs = deskRecsForSimulation(simulationParams, simulationConfig)

            futureDeskRecs.map(res => {
              Ok(write(SimulationResult(simulationParams, summary(res, simulationParams.terminal))))
            })
          case Failure(e) =>
            log.error(s"Invalid Simulation attempt: ${e.getMessage}")
            Future(BadRequest("Unable to parse parameters: " + e.getMessage))
        }
    }
  }

  private def deskRecsForSimulation(simulationParams: SimulationParams, simulationConfig: AirportConfig): Future[DeskRecMinutes] = {
    implicit val timeout: Timeout = new Timeout(10 minutes)

    val date = SDate(simulationParams.date)
    val eventualFlightsWithSplitsStream = (ctrl.actorService.portStateActor ? GetFlightsForTerminalDateRange(
      date.getLocalLastMidnight.millisSinceEpoch,
      date.getLocalNextMidnight.millisSinceEpoch,
      simulationParams.terminal
    )).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]

    FlightsRouterActor.runAndCombine(eventualFlightsWithSplitsStream).map { fws =>
      val props = Props(new ArrivalCrunchSimulationActor(
        simulationParams.applyPassengerWeighting(fws, ctrl.paxFeedSourceOrder),
      ))
      val portStateActor = actorSystem.actorOf(props)
      val deskLimits = PortDeskLimits.flexed(simulationConfig, terminalEgateBanksFromParams(simulationParams))
      Scenarios.simulationResult(
        simulationParams = simulationParams,
        simulationAirportConfig = simulationConfig,
        sla = (_: LocalDate, queue: Queue) => Future.successful(simulationParams.slaByQueue(queue)),
        splitsCalculator = SplitsCalculator(ctrl.applicationService.paxTypeQueueAllocation, airportConfig.terminalPaxSplits, ctrl.queueAdjustments),
        flightsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
        portStateActor = portStateActor,
        redListUpdatesProvider = () => ctrl.applicationService.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
        egateBanksProvider = portEgateBanksFromParams(simulationParams),
        paxFeedSourceOrder = ctrl.feedService.paxFeedSourceOrder,
        deskLimitsProviders = deskLimits,
      )
    }.flatten
  }

  private def portEgateBanksFromParams(simulationParams: SimulationParams): () => Future[PortEgateBanksUpdates] =
    () =>
      terminalEgateBanksFromParams(simulationParams)(simulationParams.terminal).map {
        case EgateBanksUpdates(updates) => PortEgateBanksUpdates(Map(simulationParams.terminal -> EgateBanksUpdates(updates)))
      }

  private def terminalEgateBanksFromParams(simulationParams: SimulationParams): Terminal => Future[EgateBanksUpdates] =
    _ => {
      val banks = simulationParams.eGateBankSizes.map(bankSize => EgateBank(IndexedSeq.fill(bankSize)(true)))
      val banksUpdates = EgateBanksUpdates(List(EgateBanksUpdate(0L, banks)))
      Future.successful(banksUpdates)
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
