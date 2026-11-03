package services.crunch.deskrecs

import drt.shared.CrunchApi.DeskRecMinute
import org.apache.pekko.Done
import org.apache.pekko.actor.{ Cancellable, Scheduler }
import org.apache.pekko.stream.Materializer
import services.TryCrunchWholePax
import services.crunch.{ CalculationAborted, CalculationControl, CalculationExecutionContext }
import services.crunch.desklimits.ConfiguredTerminalDeskLimitsLike
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.collection.immutable.NumericRange
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.NonFatal

final class QueueAllocationCoordinator(
    kind: QueueAllocationKind,
    port: String,
    mode: QueueAllocationMode,
    settings: QueueAllocationSettings,
    reporter: QueueAllocationReporter,
    controlledCruncher: CalculationControl => TryCrunchWholePax,
    demandPlannerFactory: TryCrunchWholePax => QueueDemandPlanner =
      cruncher => new BacklogAwareQueueDemandPlanner(cruncher)
)(implicit scheduler: Scheduler, coordinationEc: ExecutionContext, mat: Materializer) {
  private val reports = QueueAllocationReporter.safe(reporter)
  private val timerEc = CalculationExecutionContext.timerBookkeeping(coordinationEc)
  private val lifecycleLock = new AnyRef
  private val stopping = new AtomicBoolean(false)
  private val shutdownResult = Promise[Done]()
  private val primaryControls = new ConcurrentHashMap[CalculationControl, java.lang.Boolean]()
  private val activePrimary = new java.util.concurrent.atomic.AtomicReference[Option[PrimaryRequest]](None)

  private final class PrimaryRequest(val context: QueueAllocationContext) {
    private val finished = new AtomicBoolean(false)
    private val terminated = new AtomicBoolean(false)
    private val terminationResult = Promise[Done]()

    def termination: Future[Done] = terminationResult.future
    def isTerminated: Boolean = terminated.get()

    def complete(): Unit = {
      finished.set(true)
      releaseIfStopped()
    }

    def stopped(control: CalculationControl): Unit = {
      primaryControls.remove(control)
      releaseIfStopped()
    }

    private def releaseIfStopped(): Unit = {
      val stoppedNow = lifecycleLock.synchronized {
        if (finished.get() && primaryControls.isEmpty) {
          val current = activePrimary.get()
          if (current.exists(_ eq this)) activePrimary.compareAndSet(current, None)
          terminated.compareAndSet(false, true)
        } else false
      }
      if (stoppedNow) terminationResult.trySuccess(Done)
    }
  }

  def shutdown(): Future[Done] = {
    val (first, active) = lifecycleLock.synchronized {
      if (stopping.get()) (false, None)
      else {
        stopping.set(true)
        primaryControls.keySet().forEach(control => control.cancel("shutdown"))
        (true, activePrimary.get())
      }
    }
    if (first) active match {
      case None          => shutdownResult.trySuccess(Done)
      case Some(request) => awaitAuthoritativeTermination(request)
    }
    shutdownResult.future
  }

  private def awaitAuthoritativeTermination(request: PrimaryRequest): Unit = {
    val timer = new AtomicReference[Option[Cancellable]](None)
    request.termination.onComplete { outcome =>
      if (shutdownResult.tryComplete(outcome)) timer.get().foreach(_.cancel())
    }(ExecutionContext.parasitic)
    if (!shutdownResult.isCompleted) {
      def unfinished(reason: String): Unit = {
        val unfinishedAtDeadline = !request.isTerminated
        val report = shutdownResult.trySuccess(Done) && unfinishedAtDeadline
        if (report) reports.event(
          request.context,
          "queue_allocation_cancellation_overrun",
          "failed",
          Map("stage" -> "shutdown", "reason" -> reason)
        )
      }
      settings.shutdownGrace match {
        case None        => shutdownResult.tryFailure(new IllegalStateException("Missing authoritative shutdown grace"))
        case Some(grace) =>
          try {
            val scheduled = scheduler.scheduleOnce(grace)(unfinished("shutdown_unfinished"))(timerEc)
            timer.set(Some(scheduled))
            if (shutdownResult.isCompleted) scheduled.cancel()
          } catch {
            case NonFatal(_) => unfinished("shutdown_scheduling_failed")
          }
      }
    }
  }

  private def registerControl(control: CalculationControl): Unit = lifecycleLock.synchronized {
    primaryControls.put(control, true)
    if (stopping.get()) control.cancel("shutdown")
  }

  def ineligible(terminal: Terminal, minuteMillis: NumericRange[Long]): Unit =
    reports.event(
      QueueAllocationContext(
        "ineligible",
        kind,
        port,
        terminal.toString,
        mode,
        minuteMillis.headOption.getOrElse(0L),
        minuteMillis.lastOption.fold(0L)(_ + 60000L)
      ),
      "queue_allocation_ineligible",
      "ineligible",
      Map("reason" -> "unsupported_provider")
    )

  def calculate(
      provider: TerminalDesksAndWaitsProvider,
      terminal: Terminal,
      minuteMillis: NumericRange[Long],
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      work: Map[Queue, Seq[Double]],
      limits: ConfiguredTerminalDeskLimitsLike,
      manualDeskCapacity: Option[Int] = None
  ): Future[Iterable[DeskRecMinute]] = {
    require(mode != QueueAllocationMode.Legacy, "Legacy calculations must use the existing direct path")
    if (stopping.get()) return Future.failed(CalculationAborted("shutdown"))
    require(minuteMillis.nonEmpty, "Queue allocation requires a non-empty window")
    val context = QueueAllocationContext(
      UUID.randomUUID().toString,
      kind,
      port,
      terminal.toString,
      mode,
      minuteMillis.head,
      Math.addExact(minuteMillis.last, 60000L)
    )
    val request = new PrimaryRequest(context)
    val rejected = lifecycleLock.synchronized {
      if (stopping.get()) Some("shutdown")
      else if (activePrimary.compareAndSet(None, Some(request))) None
      else Some("primary_busy")
    }
    rejected match {
      case None =>
        scala.util.Try(neutral(
          provider,
          terminal,
          minuteMillis,
          passengers,
          work,
          limits,
          context,
          request,
          manualDeskCapacity
        ))
          .fold(Future.failed, identity)
          .andThen { case _ => request.complete() }(ExecutionContext.parasitic)
      case Some(reason) =>
        reports.event(context, "queue_allocation_request_failed", "failed", Map("reason" -> reason))
        Future.failed(CalculationAborted(reason))
    }
  }

  private def legacy(
      provider: TerminalDesksAndWaitsProvider,
      inputs: CapturedDeskCalculationInputs,
      originalLimits: ConfiguredTerminalDeskLimitsLike,
      control: CalculationControl
  )(implicit cpuEc: ExecutionContext): Future[QueueAllocationComparison.LegacyResults] =
    inputs.prepareLegacy(provider.queuePriority, control)(cpuEc).flatMap { _ =>
      control.check()
      val frozen =
        if (provider.queuePriority.exists(inputs.passengers.contains)) inputs.frozenLimits(control)(cpuEc)
        else originalLimits
      provider.copy(
        sla = (_, queue) => inputs.sla(queue, control)(cpuEc),
        cruncher = if (control eq CalculationControl.Unlimited) provider.cruncher else controlledCruncher(control)
      ).desksAndWaits(inputs.minuteMillis, inputs.passengers, frozen, control)(cpuEc, mat)
    }(cpuEc)

  private def balanced(
      inputs: CapturedDeskCalculationInputs,
      control: CalculationControl
  )(implicit
      cpuEc: ExecutionContext
  ): Future[(
      services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot,
      Map[Queue, Int],
      BalancedTerminalResult
  )] = {
    val snapshot = inputs.balancedSnapshot(control)(cpuEc)
    val slas = inputs.allSlas(control)(cpuEc)
    val acquired = snapshot.zip(slas)
    acquired.map {
      case (captured, targets) =>
        control.check()
        val cruncher = controlledCruncher(control)
        val result = BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
          captured,
          inputs.passengers,
          targets,
          demandPlannerFactory(cruncher),
          cruncher,
          control
        )
        (captured, targets, result)
    }(cpuEc)
  }

  private def neutral(
      provider: TerminalDesksAndWaitsProvider,
      terminal: Terminal,
      minuteMillis: NumericRange[Long],
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      work: Map[Queue, Seq[Double]],
      limits: ConfiguredTerminalDeskLimitsLike,
      context: QueueAllocationContext,
      request: PrimaryRequest,
      manualDeskCapacity: Option[Int]
  ): Future[Iterable[DeskRecMinute]] = {
    val execution = settings.neutral.getOrElse(throw new IllegalStateException("Missing neutral execution settings"))
    val grace = settings.cancelGrace.getOrElse(throw new IllegalStateException("Missing cancellation grace"))
    val captured = new java.util.concurrent.atomic.AtomicReference[Option[CapturedDeskCalculationInputs]](None)
    val neutralStarted = System.nanoTime()
    val primary = BudgetedCalculation.run(execution.maxDuration, grace, context, "neutral", reports, request.stopped) {
      (control, cpuEc) =>
        registerControl(control)
        control.check()
        val attempt: Future[(Iterable[DeskRecMinute], BalancedAllocationDiagnostics)] =
          CapturedDeskCalculationInputs.capture(
            minuteMillis,
            passengers,
            limits,
            provider.sla,
            control,
            manualDeskCapacity
          )(cpuEc)
            .flatMap { inputs =>
              captured.set(Some(inputs))
              inputs.prepareLegacy(provider.queuePriority, control)(cpuEc)
                .flatMap(_ => balanced(inputs, control)(cpuEc))(cpuEc)
                .map { case (snapshot, slas, result) =>
                  control.check()
                  val output = QueueAllocationComparison.validatedNeutralMinutes(
                    terminal,
                    minuteMillis,
                    inputs.passengers,
                    work,
                    snapshot,
                    slas,
                    result,
                    control
                  )
                  (output, result.diagnostics)
                }(cpuEc)
            }(cpuEc)
        attempt
    }
    primary.map { case (output, diagnostics) =>
      if (stopping.get()) throw CalculationAborted("shutdown")
      reports.event(
        context,
        "queue_allocation_neutral_completed",
        "completed",
        diagnostics.telemetryFields ++ Map(
          "authoritative_algorithm" -> "neutral",
          "duration_ms" ->
            ((System.nanoTime() - neutralStarted) / 1000000L).toString
        )
      )
      output
    }(coordinationEc).recoverWith {
      case NonFatal(error) if stopping.get()                                   => Future.failed(error)
      case error: CalculationAborted if error.reason == "cancellation_overrun" => Future.failed(error)
      case NonFatal(error)                                                     =>
        reports.event(
          context,
          "queue_allocation_fallback_started",
          "started",
          Map("reason" -> failureReason(error), "authoritative_algorithm" -> "legacy")
        )
        captured.get() match {
          case None         => Future.failed(error)
          case Some(inputs) =>
            val fallbackStarted = System.nanoTime()
            BudgetedCalculation.run(execution.fallbackDuration, grace, context, "fallback", reports, request.stopped) {
              (control, cpuEc) =>
                registerControl(control)
                control.check()
                val attempt = legacy(provider, inputs, limits, control)(cpuEc).flatMap { result =>
                  val snapshot = inputs.balancedSnapshot(control)(cpuEc)
                  val slas = inputs.allSlas(control)(cpuEc)
                  snapshot.zip(slas).map { case (raw, targets) =>
                    QueueAllocationComparison.validateFallback(raw, inputs.passengers, targets, result, control)
                    QueueAllocationComparison.strictMinutes(
                      terminal,
                      minuteMillis,
                      inputs.passengers,
                      work,
                      result,
                      control
                    )
                  }(cpuEc)
                }(cpuEc)
                attempt
            }.map { output =>
              if (stopping.get()) throw CalculationAborted("shutdown")
              reports.event(
                context,
                "queue_allocation_fallback_completed",
                "completed",
                Map(
                  "authoritative_algorithm" -> "legacy",
                  "fallback" -> "true",
                  "duration_ms" ->
                    ((System.nanoTime() - fallbackStarted) / 1000000L).toString
                )
              )
              output
            }(coordinationEc)
        }
    }(coordinationEc).andThen {
      case scala.util.Failure(error) =>
        reports.event(
          context,
          "queue_allocation_request_failed",
          "failed",
          Map("reason" -> failureReason(error))
        )
    }(coordinationEc)
  }

  private def failureReason(error: Throwable): String = error match {
    case aborted: CalculationAborted => aborted.reason
    case _                           => "calculation_failed"
  }

}
