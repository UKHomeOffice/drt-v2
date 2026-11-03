package services.crunch.deskrecs

import drt.shared.{ AllocationPreview, SimulationMinute }
import drt.shared.CrunchApi.{ PassengersMinute, StaffMinute }
import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorSystem, CoordinatedShutdown, Scheduler }
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory
import play.api.Configuration
import services.{ OptimiserWithFlexibleProcessors, TryCrunchWholePax }
import services.crunch.{ CalculationAborted, CalculationControl, CalculationExecutionContext }
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, PortDeskLimits }
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ QueuePassenger, QueueProcessor }
import uk.gov.homeoffice.drt.AppEnvironment
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.egates.EgateBanksUpdates
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{ Queue, Transfer }
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.{ LocalDate, SDate, SDateLike }

import java.util.concurrent.{ Executors, ThreadFactory }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

object AllocationPreviewService {
  case object Busy extends RuntimeException("Neutral calculation is busy")
  case object Stopping extends RuntimeException("Neutral calculation is unavailable")

  private[deskrecs] def zeroStaffResult(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      control: CalculationControl
  ): BalancedTerminalResult = {
    require(snapshot.availableStaff.exists(_.forall(_ == 0)), "Zero-staff result requires no available staff")
    require(slas.keySet == passengers.keySet && slas.values.forall(_ > 0), "Every queue requires a positive SLA")
    val desks = Vector.fill(snapshot.minuteMillis.size)(0)
    val capacity = Vector.fill(snapshot.minuteMillis.size)(List(0))
    val queues = passengers.map { case (queue, arrivals) =>
      control.check()
      val incoming = arrivals.map(_.iterator.map { load =>
        control.check()
        QueuePassenger(Math.round(load * 60).toInt)
      }.toList)
      val result = QueueProcessor.processQueueWithMetrics(capacity, incoming, slas(queue), control)
      queue -> BalancedQueueResult(
        desks,
        result.waitTimes.toVector,
        result.queueSizes.toVector,
        result.remainingPassengers.size,
        result.passengerMinutesAboveSla,
        allocationChurn = 0L
      )
    }
    BalancedTerminalResult(queues, desks, snapshot.terminalDesks, snapshot.availableStaff)
  }

  def production(
      airport: AirportConfig,
      config: Configuration,
      loads: (SDateLike, SDateLike, Terminal) => Future[Map[TQM, PassengersMinute]],
      staff: (SDateLike, SDateLike, Terminal) => Future[Vector[StaffMinute]],
      egates: Terminal => Future[EgateBanksUpdates],
      recommendationLimits: (
          Terminal => Future[EgateBanksUpdates],
          Terminal => (scala.collection.immutable.NumericRange[Long], Queue) => Future[Seq[Int]]
      ) => Map[Terminal, ConfiguredTerminalDeskLimitsLike],
      recommendationSla: (LocalDate, Queue) => Future[Int],
      deploymentSla: (LocalDate, Queue) => Future[Int],
      deploymentFairXmax: Boolean
  )(implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer): AllocationPreviewService = {
    require(
      AppEnvironment(config.getOptional[String]("env").getOrElse("other")) == AppEnvironment.PreProdEnv &&
        config.getOptional[Boolean]("feature-flags.enable-queue-allocation-preview").getOrElse(false),
      "Neutral view requires explicitly enabled preproduction configuration"
    )
    val settings = AllocationPreviewSettings.fromConfiguration(config)
    val service = new AllocationPreviewService(
      settings,
      (request, control, executor) => {
        val cpu: ExecutionContext = executor
        require(Set("recommended", "available").contains(request.kind), "Invalid neutral staffing view")
        require(airport.terminalsForDate(request.date).exists(_ == request.terminal), "Invalid neutral terminal")
        val update = TerminalUpdateRequest(request.terminal, request.date)
        val minutes: scala.collection.immutable.NumericRange[Long] =
          update.minutesInMillis(airport.crunchOffsetMinutes)
        require(minutes.nonEmpty && minutes.size <= 1500, "Invalid neutral calculation window")
        val start = update.start.addMinutes(airport.crunchOffsetMinutes)
        val end = update.end.addMinutes(airport.crunchOffsetMinutes)
        val queues = QueueConfig.queuesForDateRangeAndTerminal(airport.queuesByTerminal)(
          SDate(minutes.head).toLocalDate,
          SDate(minutes.last).toLocalDate,
          request.terminal
        ).filterNot(_ == Transfer)
        require(queues.nonEmpty && queues.size <= 32, "Invalid neutral queue coverage")
        val incoming = CalculationExecutionContext.trackInput(loads(start, end, request.terminal))(cpu)
        val staffing = CalculationExecutionContext.trackInput(staff(start, end, request.terminal))(cpu)
        val banks = CalculationExecutionContext.trackInput(egates(request.terminal))(cpu)
        incoming.zip(staffing).flatMap { case (rawLoads, rawStaff) =>
          control.check()
          val source = rawLoads.filterNot(_._1.queue == Transfer)
          require(
            source.keys.forall(key =>
              key.terminal == request.terminal && queues.contains(key.queue) && minutes.contains(key.minute)
            ),
            "Invalid neutral load coverage"
          )
          val remaining = new AtomicInteger(settings.maxPassengers)
          val passengers: Map[Queue, IndexedSeq[Iterable[Double]]] = queues.map { queue =>
            queue -> minutes.map { minute =>
              source.get(TQM(request.terminal, queue, minute)).iterator.flatMap(_.passengers.iterator).map { load =>
                control.check()
                require(remaining.decrementAndGet() >= 0, "Neutral passenger entry limit exceeded")
                require(
                  load.isFinite && load >= 0 && Math.round(load * 60) <= Int.MaxValue.toLong,
                  "Invalid neutral load"
                )
                load
              }.toVector
            }
          }.toMap
          val indexedStaff = rawStaff.map(value => value.minute -> value).toMap
          require(
            rawStaff.size == indexedStaff.size && rawStaff.forall(value =>
              value.terminal == request.terminal && minutes.contains(value.minute)
            ),
            "Invalid neutral staff coverage"
          )
          val staffMinutes = minutes.map(minute =>
            indexedStaff.getOrElse(minute, StaffMinute(request.terminal, minute, 0, 0, 0, None))
          ).toVector
          val counts: Terminal => (scala.collection.immutable.NumericRange[Long], Queue) => Future[Seq[Int]] =
            _ =>
              (range, queue) => {
                require(range == minutes, "Neutral count window mismatch")
                Future.successful(passengers(queue).map(_.size))
              }
          val recommended = recommendationLimits(_ => banks, counts)(request.terminal)
          val available = PortDeskLimits.flexedByAvailableStaff(airport, _ => banks, counts)(
            request.terminal,
            staffMinutes.map(_.availableAtPcp).toList
          )(cpu)
          val work = passengers.map { case (queue, values) => queue -> values.map(_.sum) }
          def calculate(
              limits: ConfiguredTerminalDeskLimitsLike,
              sla: (LocalDate, Queue) => Future[Int],
              useFairXmax: Boolean,
              deployment: Boolean
          ): Future[AllocationPreview.Result] =
            CapturedDeskCalculationInputs.capture(
              minutes,
              passengers,
              limits,
              sla,
              control,
              Some(airport.desksByTerminal(request.terminal))
            )(cpu).flatMap { inputs =>
              inputs.balancedSnapshot(control)(cpu).zip(inputs.allSlas(control)(cpu)).flatMap { case (snapshot, slas) =>
                val cruncher: TryCrunchWholePax =
                  OptimiserWithFlexibleProcessors.crunchWholePaxControlled(useFairXmax, control)
                // With no staff, zero is the only feasible schedule; demand search cannot change it.
                val result = if (deployment && snapshot.availableStaff.exists(_.forall(_ == 0)))
                  zeroStaffResult(snapshot, passengers, slas, control)
                else BalancedTerminalDesksAndWaitsProvider.calculate(snapshot, passengers, slas, cruncher, control)
                val rows = QueueAllocationComparison.validatedNeutralMinutes(
                  request.terminal,
                  minutes,
                  passengers,
                  work,
                  snapshot,
                  slas,
                  result,
                  control
                )
                val values = rows.iterator.map { row =>
                  if (!deployment) row.toMinute
                  else SimulationMinute(
                    row.terminal,
                    row.queue,
                    row.minute,
                    row.deskRec,
                    row.waitTime,
                    row.maybePaxInQueue
                  )
                    .toMinute.copy(paxLoad = row.paxLoad, workLoad = row.workLoad)
                }.toVector
                AllocationMeasurements.compare(
                  request.terminal,
                  inputs,
                  snapshot,
                  slas,
                  result,
                  airport.queuePriority,
                  cruncher,
                  control
                )(cpu, mat).map { measurements =>
                  AllocationPreview.Result(queues, values, staffMinutes, slas, Some(measurements))
                }(cpu)
              }(cpu)
            }(cpu)
          val selected = if (request.kind == "recommended")
            calculate(recommended, recommendationSla, useFairXmax = true, deployment = false)
          else calculate(available, deploymentSla, deploymentFairXmax, deployment = true)
          selected.map { result =>
            AllocationPreview.Response(
              minutes.head,
              minutes.last + 60000L,
              Option.when(request.kind == "recommended")(result),
              Option.when(request.kind == "available")(result)
            )
          }(cpu)
        }(cpu)
      }
    )(system.scheduler, ec)
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "shutdown-neutral-view"
    )(() => service.shutdown())
    service
  }
}

/** One non-publishing request at a time; no sessions, stored results or status polling. */
final class AllocationPreviewService private[deskrecs] (
    settings: AllocationPreviewSettings,
    calculateInputs: (
        AllocationPreview.Request,
        CalculationControl,
        ExecutionContext
    ) => Future[AllocationPreview.Response]
)(implicit scheduler: Scheduler, coordinationEc: ExecutionContext) {
  private final class Operation {
    val control = new AtomicReference(Option.empty[CalculationControl])
    val finished = new AtomicBoolean(false)
    val stopped = new AtomicBoolean(false)
    val terminated = Promise[Done]()
  }
  private val log = LoggerFactory.getLogger(getClass)
  private val active = new AtomicReference(Option.empty[Operation])
  private val stopping = new AtomicBoolean(false)
  private val shutdownResult = Promise[Done]()
  // One calculation is admitted at a time, so a single dedicated thread needs no bounded queue.
  private val worker = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "neutral-view")
      thread.setDaemon(true)
      thread
    }
  })
  private val workerEc = ExecutionContext.fromExecutor(worker)
  private[deskrecs] def activeWork: Boolean = active.get().nonEmpty
  private[deskrecs] def workerStopped: Boolean = worker.isTerminated

  def calculate(request: AllocationPreview.Request): Future[AllocationPreview.Response] = synchronized {
    val operation = new Operation
    if (stopping.get()) Future.failed(AllocationPreviewService.Stopping)
    else if (!active.compareAndSet(None, Some(operation))) Future.failed(AllocationPreviewService.Busy)
    else {
      val context = QueueAllocationContext(
        "neutral-view",
        QueueAllocationKind.Recommendations,
        "neutral-view",
        request.terminal.toString,
        QueueAllocationMode.Neutral,
        0L,
        0L
      )
      val result = Try(BudgetedCalculation.run(
        settings.budget,
        settings.cancelGrace,
        context,
        "neutral",
        QueueAllocationReporter.NoOp,
        _ => { operation.stopped.set(true); release(operation) },
        Some(workerEc)
      ) { (control, executor) =>
        operation.control.set(Some(control))
        if (stopping.get()) control.cancel("shutdown")
        Future.unit.flatMap { _ =>
          control.check()
          calculateInputs(request, control, executor)
        }(executor)
      }).fold(
        error => {
          operation.stopped.set(true)
          Future.failed(error)
        },
        identity
      )
      result.onComplete { _ =>
        operation.finished.set(true)
        release(operation)
      }(ExecutionContext.parasitic)
      result
    }
  }

  private def release(operation: Operation): Unit =
    if (operation.finished.get() && operation.stopped.get()) {
      val current = active.get()
      if (current.contains(operation)) active.compareAndSet(current, None)
      operation.terminated.trySuccess(Done)
      if (stopping.get()) { worker.shutdown(); shutdownResult.trySuccess(Done) }
    }

  def shutdown(): Future[Done] = synchronized {
    if (stopping.compareAndSet(false, true)) {
      active.get() match {
        case None            => worker.shutdown(); shutdownResult.trySuccess(Done)
        case Some(operation) =>
          operation.control.get().foreach(_.cancel("shutdown"))
          val timer = Try(scheduler.scheduleOnce(settings.shutdownGrace) {
            log.warn("Neutral view shutdown has unfinished work; admission remains closed")
            shutdownResult.trySuccess(Done)
          }(CalculationExecutionContext.timerBookkeeping(coordinationEc)))
          timer match {
            case Success(value) => operation.terminated.future.foreach(_ => value.cancel())(ExecutionContext.parasitic)
            case Failure(_)     =>
              log.warn("Neutral view shutdown timer rejected; admission remains closed")
              shutdownResult.trySuccess(Done)
          }
      }
    }
    shutdownResult.future
  }
}
