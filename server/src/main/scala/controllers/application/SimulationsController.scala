package controllers.application

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.routing.FlightsRouterActor
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import drt.shared.CrunchApi._
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.apache.commons.math3.analysis.MultivariateFunction
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.{NelderMeadSimplex, SimplexOptimizer}
import org.apache.commons.math3.optim.nonlinear.scalar.{GoalType, ObjectiveFunction}
import org.apache.commons.math3.optim.{InitialGuess, MaxEval}
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
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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
      val maybeAdultChildRatio = request.queryString.get("adultChildRatio").flatMap(_.headOption).map(_.toDouble)
      val terminal = Terminal(terminalName)
      val childParentRatio = maybeAdultChildRatio.getOrElse(1d)

      val bxAndDrtStatsForDate = bxAndDrtStatsForUptakeAndAdultChildRatio()(uptakePercentage, childParentRatio)

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
            val stream: Source[(UtcDate, Terminal, Int, Double, Double, Int, Double), NotUsed] = Source(dates)
              .mapAsync(1) { case (date, idx) =>
                bxAndDrtStatsForDate(date, terminal).map {
                  case (bxTotalPax, bxPercentage, bxUptakePct, drtTotalPax, drtPercentage) =>
                    val progressPercentage = ((idx + 1).toDouble / totalDates) * 100
                    ctrl.aggregatedDb.run(
                      dao.insertOrUpdate(egateSimulation.copy(status = s"processing - ${progressPercentage.toInt}% complete"))
                    )
                    (date, terminal, bxTotalPax, bxPercentage, bxUptakePct, drtTotalPax, drtPercentage)
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

  def optimiseEgates(terminalName: String, startDate: String, endDate: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      val terminal = Terminal(terminalName)

      val startUptakePercentage = 93d
      val statsForDate = bxAndDrtStatsForUptakeAndAdultChildRatio()

      val uptakePctForDate: (Double, UtcDate) => Future[Double] =
        (adultChildRatio, date) => {
          val fn = statsForDate(startUptakePercentage, adultChildRatio)
          fn(date, terminal).map(_._3)
        }
      val start = UtcDate.parse(startDate).getOrElse(throw new IllegalArgumentException(s"Invalid start date: $startDate"))
      val end = UtcDate.parse(endDate).getOrElse(throw new IllegalArgumentException(s"Invalid end date: $endDate"))

      val dates = DateRange(start, end)

      def uptakeStdDev(x: Double): Double = {
        val ys = dates
          .map(date => Await.result(uptakePctForDate(x, date), 30.second))
          .filter(_y => 60 <= _y && _y <= 100)

        if (ys.size < (dates.size.toDouble * 0.85)) {
          log.warning(s"Not enough data points for adult-child ratio $x: ${ys.size} out of ${dates.size}")
          1e9 // Heavy penalty for insufficient data
        } else {
          log.info(s"Uptake percentages for adult-child ratio $x ($x: ${ys.size} out of ${dates.size}): ${ys.mkString(", ")}")
          val mean = ys.sum / ys.length
          math.sqrt(ys.map(y => math.pow(y - mean, 2)).sum / ys.length)
        }
      }

      val bestAdultChildRatio = optimiseWithBounds(uptakeStdDev, 1d, 3d, 1.5)

      val drtVsBxDiffPctForDate: (Double, UtcDate) => Future[Double] =
        (uptakePct, date) => {
          val fn = statsForDate(uptakePct, bestAdultChildRatio)
          fn(date, terminal).map {
            case (_, bxEgatePct, _, _, drtEgatePct) => Math.abs((drtEgatePct - bxEgatePct) / bxEgatePct * 100)
          }
        }

      def egateMeanDiff(x: Double): Double = {
        val ys = dates
          .map(date => Await.result(drtVsBxDiffPctForDate(x, date), 30.second))
          .filter(_y => -10 <= _y &&  _y <= 10)

        ys.sum / ys.length
      }

      val bestUptakePct = optimiseWithBounds(egateMeanDiff, 85, 98, 90)

      Ok(
        f"""{
           |  "bestAdultChildRatio": $bestAdultChildRatio%.5f,
           |  "bestUptakePercentage": $bestUptakePct%.5f
           |}""".stripMargin)
    }
  }

  private def optimiseWithBounds(objective: Double => Double,
                                 lowerBound: Double,
                                 upperBound: Double,
                                 initialGuess: Double): Double = {
    val boundedObjective = new MultivariateFunction {
      override def value(x: Array[Double]): Double = {
        val x0 = x(0)
        if (x0 < lowerBound || x0 > upperBound) 1e9 // Heavy penalty
        else objective(x0)
      }
    }

    val optimizer = new SimplexOptimizer(1e-8, 1e-10)
    val simplex = new NelderMeadSimplex(1)

    optimizer.optimize(
      new MaxEval(200),
      new ObjectiveFunction(boundedObjective),
      GoalType.MINIMIZE,
      new InitialGuess(Array(initialGuess)),
      simplex
    ).getPoint()(0)
  }

  private def bxAndDrtStatsForUptakeAndAdultChildRatio(): (Double, Double) => (UtcDate, Terminal) => Future[(Int, Double, Double, Int, Double)] = {
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
    val storeEgateEligibility: (UtcDate, Terminal, Int, Double, Double) => Unit =
      (date, terminal, tot, el, ua) =>
        ctrl.aggregatedDb.run(egateEligibilityDao.insertOrUpdate(EgateEligibility(airportConfig.portCode, terminal, date, tot, el, ua, SDate.now())))

    val calcEgateEligibleAndUnderAgeForDate = EgateSimulations.drtTotalPaxEgateEligiblePctAndUnderAgePctForDate(
      arrivalsWithManifestsForDateAndTerminal = arrivalsWithManifests,
      egateEligibleAndUnderAgePct = EgateSimulations.egateEligibleAndUnderAgePct(paxTypeAllocator),
      storeEgateEligibleAndUnderAgeForDate = storeEgateEligibility,
    )

    val drtEgatePercentage: (Double, Double) => (UtcDate, Terminal) => Future[(Int, Double, Double)] = {
      (uptakePercentage, adultChildRatio) =>
        EgateSimulations.drtTotalAndEgateEligibleAndActualPercentageForDateAndTerminal(uptakePercentage)(
          cachedEgateEligibleAndUnderAgeForDate = (terminal, date) => ctrl.aggregatedDb.run(egateEligibilityDao.get(airportConfig.portCode, date, terminal)),
          drtTotalPaxEgateEligiblePctAndUnderAgePctForDate = calcEgateEligibleAndUnderAgeForDate,
          netEligiblePercentage = EgateSimulations.netEgateEligiblePct(adultChildRatio),
        )
    }

    val bxDao = BorderCrossingDao
    val bxQueueTotals: (UtcDate, Terminal) => Future[Map[Queue, Int]] =
      (date, terminal) => {
        val function = bxDao.queueTotalsForPortAndDate(airportConfig.portCode.iata, Some(terminal.toString))
        ctrl.aggregatedDb.run(function(date))
      }
    val bxEgatePercentage = EgateSimulations.bxTotalPaxAndEgatePctForDateAndTerminal(bxQueueTotals)

    (up: Double, acr: Double) => EgateSimulations.bxAndDrtStatsForDate(bxEgatePercentage, drtEgatePercentage(up, acr), EgateSimulations.bxUptakePct)
  }

  private def createResponse(rows: Seq[(UtcDate, Terminal, Int, Double, Double, Int, Double)]) = {
    val headerRow = "Date,Terminal,BX Total Pax,BX EGate %,BX uptake %,DRT Total Pax,DRT EGate %,Difference %\n"

    val csvContent = headerRow + rows
      .map { case (date, terminal, bxTotalPax, bxPercentage, bxUptakePct, drtTotalPax, drtPercentage) =>
        val diffPct = (drtPercentage - bxPercentage) / bxPercentage * 100
        f"${date.toISOString},${terminal.toString},$bxTotalPax,$bxPercentage%.2f,$bxUptakePct%.2f,$drtTotalPax,$drtPercentage%.2f,$diffPct%.2f\n"
      }
      .mkString

    val actuals = rows.map { case (_, _, _, bx, _, _, _) => bx }
    val estimates = rows.map { case (_, _, _, _, _, _, drt) => drt }

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
