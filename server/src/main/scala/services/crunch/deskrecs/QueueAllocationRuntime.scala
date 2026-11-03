package services.crunch.deskrecs

import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorSystem, CoordinatedShutdown, Scheduler }
import org.apache.pekko.stream.Materializer
import services.TryCrunchWholePax
import services.crunch.CalculationControl

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.util.Try

final class QueueAllocationRuntime(
    settings: QueueAllocationSettings,
    reporter: QueueAllocationReporter
)(implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer) {
  private implicit val scheduler: Scheduler = system.scheduler
  private val reports = QueueAllocationReporter.safe(reporter)
  private val coordinators = new ConcurrentLinkedQueue[QueueAllocationCoordinator]()
  private val lifecycleLock = new AnyRef
  private val stopping = new AtomicBoolean(false)
  private val shutdownResult = Promise[Done]()
  if (settings.enabled) {
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "shutdown-queue-allocation"
    )(() => shutdown())
  }

  def coordinator(
      kind: QueueAllocationKind,
      port: String,
      cruncher: CalculationControl => TryCrunchWholePax,
      demandPlannerFactory: TryCrunchWholePax => QueueDemandPlanner =
        value => new BacklogAwareQueueDemandPlanner(value)
  ): Option[QueueAllocationCoordinator] = {
    val mode = settings.mode(kind)
    val configured = lifecycleLock.synchronized {
      require(!stopping.get(), "Queue allocation runtime is shutting down")
      if (mode == QueueAllocationMode.Legacy) None
      else {
        val coordinator = new QueueAllocationCoordinator(
          kind,
          port,
          mode,
          settings,
          reports,
          cruncher,
          demandPlannerFactory
        )
        coordinators.add(coordinator)
        Some(coordinator)
      }
    }
    reports.event(
      QueueAllocationContext("startup", kind, port, "all", mode, 0L, 0L),
      "queue_allocation_configured",
      "configured",
      Map.empty[String, String] ++
        settings.neutral.map(value => "neutral_max_duration_ms" -> value.maxDuration.toMillis.toString) ++
        settings.neutral.map(value => "fallback_max_duration_ms" -> value.fallbackDuration.toMillis.toString) ++
        settings.cancelGrace.map(value => "cancel_grace_ms" -> value.toMillis.toString) ++
        settings.shutdownGrace.map(value => "shutdown_grace_ms" -> value.toMillis.toString)
    )
    configured
  }

  def shutdown(): Future[Done] = {
    val (first, current) = lifecycleLock.synchronized {
      if (stopping.get()) (false, Vector.empty[QueueAllocationCoordinator])
      else {
        stopping.set(true)
        (true, coordinators.iterator().asScala.toVector)
      }
    }
    if (first) {
      val actions = current.map(coordinator => () => coordinator.shutdown())
      val pending: Vector[Future[Done]] = actions.map(action => Try(action()).fold(Future.failed, identity))
      val completed: Future[Vector[Done]] = Future.sequence(pending)(implicitly, ExecutionContext.parasitic)
      completed.onComplete { outcome =>
        shutdownResult.tryComplete(outcome.map(_ => Done))
        ()
      }(ExecutionContext.parasitic)
    }
    shutdownResult.future
  }
}
