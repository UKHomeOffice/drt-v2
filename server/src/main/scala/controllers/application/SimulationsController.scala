package controllers.application

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.routing.FlightsRouterActor
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import drt.shared.CrunchApi._
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import play.api.mvc._
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.OptimisationProviders
import services.exports.StreamingDesksExport
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.{Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSimulationUpload, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.dao.BorderCrossingDao
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM, UniqueArrivalKey}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.EgateUptakeSimulation
import uk.gov.homeoffice.drt.time._
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

  def egateUptakeSimulation(startDate: String, endDate: String, uptakePercentage: Double): Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async {
      val queueAllocation = EgateUptakeSimulation.queueAllocationForEgateUptake(airportConfig.terminalPaxTypeQueueAllocation, uptakePercentage)
      val splitsCalc = EgateUptakeSimulation.splitsCalculatorForPaxAllocation(airportConfig, queueAllocation)
      val egateAndDeskPaxForFlight = EgateUptakeSimulation.egateAndDeskPaxForFlight(splitsCalc)
      val historicManifest: UniqueArrivalKey => Future[Option[BestAvailableManifest]] = (ua: UniqueArrivalKey) => ctrl.applicationService.manifestLookupService.maybeBestAvailableManifest(ua.arrivalPort, ua.departurePort, ua.voyageNumber, ua.scheduled).map(_._2)
      val flightsWithPcpStartDuringDate: (UtcDate, Terminal) => Future[Seq[Arrival]] =
        (d: UtcDate, t: Terminal) => {
          val start = SDate(d)
          val end = start.addDays(1).addMinutes(-1)
          ctrl
            .flightsForPcpDateRange(d, d, Seq(t))
            .map { case (_, flights) =>
              flights.collect {
                case fws if fws.apiFlight.hasPcpDuring(start, end, ctrl.feedService.paxFeedSourceOrder) => fws.unique -> fws.apiFlight
              }.toMap.values
            }
            .runWith(Sink.seq)
            .map(_.flatten)
        }
      val arrivalsWithManifests = EgateUptakeSimulation.arrivalsWithManifestsForDateAndTerminal(
        portCode = airportConfig.portCode,
        liveManifest = ctrl.applicationService.manifestProvider,
        historicManifest = historicManifest,
        flightsForDateAndTerminal = flightsWithPcpStartDuringDate,
      )
      val drtEgatePercentage: (UtcDate, Terminal) => Future[Double] = EgateUptakeSimulation.drtEgatePercentageForDateAndTerminal(
        flightsWithManifestsForDateAndTerminal = arrivalsWithManifests,
        egateAndDeskPaxForFlight = egateAndDeskPaxForFlight,
      )
      val bxDao = BorderCrossingDao
      val bxQueueTotals: (UtcDate, Terminal) => Future[Map[Queue, Int]] =
        (date, terminal) => {
          val function = bxDao.queueTotalsForPortAndDate(airportConfig.portCode.iata, Some(terminal.toString))
          ctrl.aggregatedDb.run(function(date))
        }
      val bxEgatePercentage: (UtcDate, Terminal) => Future[Double] = EgateUptakeSimulation.bxEgatePercentageForDateAndTerminal(bxQueueTotals)

      val bxAndDrtEgatePercentageForDate = EgateUptakeSimulation.bxAndDrtEgatePercentageForDate(bxEgatePercentage, drtEgatePercentage)

      val start = UtcDate.parse(startDate).getOrElse(throw new IllegalArgumentException(s"Invalid start date: $startDate"))
      val end = UtcDate.parse(endDate).getOrElse(throw new IllegalArgumentException(s"Invalid end date: $endDate"))

      val lines: Source[Seq[(UtcDate, Terminal, Double, Double)], NotUsed] = Source(DateRange(start, end))
        .mapAsync(1) { date =>
          Source(airportConfig.terminals(SDate(date).toLocalDate).toSeq)
            .mapAsync(1) { terminal =>
              bxAndDrtEgatePercentageForDate(date, terminal).map {
                case (bxPercentage, drtPercentage) => (date, terminal, bxPercentage, drtPercentage)
              }
            }
            .runWith(Sink.seq)
        }
      val content = lines
        .runWith(Sink.seq)
        .map { dates =>
          val c = dates.flatten
            .groupBy(_._2).map {
              case (terminal, figures) =>
                //average bx & drt percentages for the terminal
                val bxAverage = figures.map(_._3).sum / figures.size
                val drtAverage = figures.map(_._4).sum / figures.size
                (terminal, bxAverage, drtAverage)
            }

          Ok(c.mkString("\n"))
        }

      content
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
        splitsCalculator = ctrl.splitsCalculator,
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

  def summary(mins: DeskRecMinutes, terminal: Terminal): Map[Queues.Queue, List[CrunchMinute]] = {
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
        Seq(simulationParams.terminal),
        SDate(simulationParams.date),
        SDate(simulationParams.date),
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
