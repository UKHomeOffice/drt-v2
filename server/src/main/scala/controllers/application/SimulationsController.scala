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
import uk.gov.homeoffice.drt.db.dao.{BorderCrossingDao, EgateEligibilityDao, EgateSimulationDao}
import uk.gov.homeoffice.drt.db.serialisers.{EgateEligibility, EgateSimulation, EgateSimulationRequest, EgateSimulationResponse}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.models._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.EgateSimulations
import uk.gov.homeoffice.drt.time._
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{existentials, postfixOps}
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

  def doEgateSimulation(terminalName: String, startDate: String, endDate: String, uptakePercentage: Double): Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async { request =>
      val maybeChildParentRatio = request.queryString.get("childParentRatio").flatMap(_.headOption).map(_.toDouble)
      val terminal = Terminal(terminalName)
      val childParentRatio = maybeChildParentRatio.getOrElse(1d)
      val historicManifest: UniqueArrivalKey => Future[Option[BestAvailableManifest]] =
        (ua: UniqueArrivalKey) =>
          ctrl.applicationService.manifestLookupService.maybeBestAvailableManifest(ua.arrivalPort, ua.departurePort, ua.voyageNumber, ua.scheduled).map(_._2)
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
      val arrivalsWithManifests = EgateSimulations.arrivalsWithManifestsForDateAndTerminal(
        portCode = airportConfig.portCode,
        liveManifest = ctrl.applicationService.manifestProvider,
        historicManifest = historicManifest,
        arrivalsForDateAndTerminal = flightsWithPcpStartDuringDate,
      )
      val paxTypeAllocator = if (airportConfig.hasTransfer) B5JPlusWithTransitTypeAllocator else B5JPlusTypeAllocator
      val egateEligibilityDao = EgateEligibilityDao()
      val storeEgateEligibility: (UtcDate, Terminal, Int, Int, Int) => Unit =
        (date, terminal, tot, el, ua) =>
          ctrl.aggregatedDb.run(egateEligibilityDao.insertOrUpdate(EgateEligibility(airportConfig.portCode, terminal, date, tot, el, ua, SDate.now())))

      val drtEgatePercentage: (UtcDate, Terminal) => Future[(Double, Double)] =
        EgateSimulations.drtEgateEligibleAndActualPercentageForDateAndTerminal(uptakePercentage)(
          arrivalsWithManifestsForDateAndTerminal = arrivalsWithManifests,
          egateEligibleAndUnderAgeForDate = (terminal, date) => ctrl.aggregatedDb.run(egateEligibilityDao.get(airportConfig.portCode, date, terminal)),
          storeEgateEligibleAndUnderAgeForDate = storeEgateEligibility,
          egateEligibleAndUnderAgePercentages = EgateSimulations.egateEligibleAndUnderAgePercentages(paxTypeAllocator),
          eligiblePercentage = EgateSimulations.egateEligiblePercentage(childParentRatio),
        )
      val bxDao = BorderCrossingDao
      val bxQueueTotals: (UtcDate, Terminal) => Future[Map[Queue, Int]] =
        (date, terminal) => {
          val function = bxDao.queueTotalsForPortAndDate(airportConfig.portCode.iata, Some(terminal.toString))
          ctrl.aggregatedDb.run(function(date))
        }
      val bxEgatePercentage: (UtcDate, Terminal) => Future[Double] = EgateSimulations.bxEgatePercentageForDateAndTerminal(bxQueueTotals)

      val bxAndDrtEgatePctAndBxUptakePctForDate =
        EgateSimulations.bxAndDrtEgatePctAndBxUptakePctForDate(bxEgatePercentage, drtEgatePercentage, EgateSimulations.bxUptakePct)

      val start = UtcDate.parse(startDate).getOrElse(throw new IllegalArgumentException(s"Invalid start date: $startDate"))
      val end = UtcDate.parse(endDate).getOrElse(throw new IllegalArgumentException(s"Invalid end date: $endDate"))

      val egateSimulationRequest = EgateSimulationRequest(ctrl.airportConfig.portCode, terminal, start, end, uptakePercentage, childParentRatio)
      val dao = EgateSimulationDao()

      ctrl.aggregatedDb.run(dao.get(egateSimulationRequest)).flatMap {
        case Some(existingSimulation) =>
          log.info(s"Egate uptake simulation already exists for request: $egateSimulationRequest with UUID: ${existingSimulation.uuid}")
          Future.successful(Ok(existingSimulation.uuid))
        case None =>
          val egateSimulation = EgateSimulation(
            uuid = UUID.randomUUID().toString(),
            request = egateSimulationRequest,
            status = "processing",
            response = None,
            createdAt = SDate.now(),
          )
          ctrl.aggregatedDb.run(dao.insertOrUpdate(egateSimulation)).map { _ =>
            log.info(s"Starting egate uptake simulation for terminal $terminal from $start to $end with uptake percentage $uptakePercentage%")

            val dates = DateRange(start, end).zipWithIndex
            val totalDates = dates.size
            val stream: Source[(UtcDate, Terminal, Double, Double, Double), NotUsed] = Source(dates)
              .mapAsync(1) { case (date, idx) =>
                bxAndDrtEgatePctAndBxUptakePctForDate(date, terminal).map {
                  case (bxPercentage, drtPercentage, bxUptakePct) =>
                    val progressPercentage = ((idx + 1).toDouble / totalDates) * 100
                    ctrl.aggregatedDb.run(
                      dao.insertOrUpdate(egateSimulation.copy(status = s"processing - ${progressPercentage.toInt}% complete"))
                    )
                    (date, terminal, bxPercentage, drtPercentage, bxUptakePct)
                }
              }

            stream
              .runWith(Sink.seq)
              .map { rows =>
                val response: EgateSimulationResponse = createResponse(rows)
                ctrl.aggregatedDb.run(dao.insertOrUpdate(egateSimulation.copy(status = "completed", response = Some(response))))
              }
              .recover {
                case e: Exception =>
                  log.error(s"Error during egate uptake simulation: ${e.getMessage}")
                  ctrl.aggregatedDb.run(dao.insertOrUpdate(egateSimulation.copy(status = "failed", response = None)))
              }

            Ok(egateSimulation.uuid)
          }
      }
    }
  }

  private def createResponse(rows: Seq[(UtcDate, Terminal, Double, Double, Double)]) = {
    val headerRow = "Date,Terminal,BX EGate %, BX EGate uptake %,DRT EGate %,Difference %\n"

    val csvContent = headerRow + rows
      .map { case (date, terminal, bxPercentage, drtPercentage, bxUptakePct) =>
        val diffPct = (drtPercentage - bxPercentage) / bxPercentage * 100
        f"${date.toISOString},${terminal.toString},$bxPercentage%.2f,$bxUptakePct%.2f,$drtPercentage%.2f,$diffPct%.2f\n"
      }
      .mkString

    val actuals = rows.map { case (_, _, bx, _, _) => bx }
    val estimates = rows.map { case (_, _, _, drt, _) => drt }

    val (bias, mape, stdDev, correlation, rSquared) = stats(actuals, estimates)

    val response = EgateSimulationResponse(
      csvContent = csvContent,
      meanAbsolutePercentageError = mape,
      standardDeviation = stdDev,
      bias = bias,
      correlationCoefficient = correlation,
      rSquaredError = rSquared,
    )
    response
  }

  private def stats(actuals: Seq[Double], estimates: Seq[Double]): (Double, Double, Double, Double, Double) = {
    val n = estimates.length

    val errors = estimates.zip(actuals).map { case (e, a) => e - a }
    val bias = errors.sum / n

    val mape = estimates.zip(actuals).collect {
      case (e, a) if a != 0 =>
        math.abs((e - a) / a)
    }.sum / actuals.count(_ != 0) * 100

    val stdDev = {
      val meanError = bias
      math.sqrt(errors.map(e => math.pow(e - meanError, 2)).sum / n)
    }

    val estimateMean = estimates.sum / n
    val actualMean = actuals.sum / n

    val covariance = estimates.zip(actuals).map { case (e, a) =>
      (e - estimateMean) * (a - actualMean)
    }.sum / n

    val estimateVariance = estimates.map(e => math.pow(e - estimateMean, 2)).sum / n
    val actualVariance = actuals.map(a => math.pow(a - actualMean, 2)).sum / n

    val correlation = covariance / math.sqrt(estimateVariance * actualVariance)

    val ssRes = estimates.zip(actuals).map { case (e, a) => math.pow(a - e, 2) }.sum
    val ssTot = actuals.map(a => math.pow(a - actualMean, 2)).sum
    val rSquared = 1 - (ssRes / ssTot)

    (bias, mape, stdDev, correlation, rSquared)
  }

  def getEgateSimulation(uuid: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async {
      val dao = EgateSimulationDao()
      ctrl.aggregatedDb.run(dao.get(uuid)).map {
        case Some(simulation) =>
          (simulation.status, simulation.response) match {
            case ("completed", Some(response)) => Ok(response.csvContent).as("text/csv")
            case (status, _) => NotFound(s"$uuid: $status")
          }
        case None => NotFound(s"Egate simulation with UUID: $uuid not found")
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
