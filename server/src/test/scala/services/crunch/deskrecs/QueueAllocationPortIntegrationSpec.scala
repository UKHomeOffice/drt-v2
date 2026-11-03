package services.crunch.deskrecs

import drt.shared.CrunchApi.PassengersMinute
import org.apache.pekko.actor.{ ActorSystem, Scheduler }
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.CalculationControl
import services.crunch.desklimits.{ DeskCapacityProvider, QueueCapacityProvider }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import services.graphstages.{ DynamicWorkloadCalculator, FlightFilter }
import services.{ OptimizerCrunchResult, TryCrunchWholePax }
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue, QueueFallbacks, Transfer }
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.NumericRange
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

class QueueAllocationPortIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("queue-allocation-port-test")
  private implicit val scheduler: Scheduler = system.scheduler
  private implicit val materializer: Materializer = Materializer(system)
  private val start = SDate("2020-01-01T00:00").millisSinceEpoch
  private val minutes: NumericRange[Long] = start until (start + 30 * 60000L) by 60000L
  private val maximum: TryCrunchWholePax = (passengers, _, max, _) =>
    Success(OptimizerCrunchResult(
      max.toIndexedSeq,
      Seq.fill(passengers.size)(0),
      IndexedSeq.fill(passengers.size)(0d)
    ))
  private val settings = QueueAllocationSettings(
    recommendations = QueueAllocationMode.Neutral,
    deployments = QueueAllocationMode.Neutral,
    neutral = Some(NeutralAllocationSettings(3.seconds, 3.seconds)),
    cancelGrace = Some(200.millis),
    shutdownGrace = Some(1.second)
  )

  private class Events extends QueueAllocationReporter {
    val names = new ConcurrentLinkedQueue[String]()
    override def event(
        context: QueueAllocationContext,
        name: String,
        outcome: String,
        fields: Map[String, String]
    ): Unit = { names.add(name); () }
    override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()
  }

  private def port(
      queues: Seq[Queue],
      coordinator: Option[QueueAllocationCoordinator] = None
  ): PortDesksAndWaitsProvider =
    PortDesksAndWaitsProvider(
      queuesByTerminal = (_, _, _) => queues,
      divertedQueues = Map.empty,
      desksByTerminal = Map(T1 -> 4),
      flexedQueuesPriority = List(EeaDesk),
      sla = (_, _) => Future.successful(9),
      terminalProcessingTimes = Map.empty,
      minutesToCrunch = 30,
      crunchOffsetMinutes = 0,
      tryCrunch = maximum,
      workloadCalculator = DynamicWorkloadCalculator(
        Map.empty,
        QueueFallbacks((_, _) => Seq.empty),
        FlightFilter(List()),
        45,
        identity
      ),
      paxFeedSourceOrder = List(LiveFeedSource),
      allocationCoordinator = coordinator
    )

  private def loads(queues: Seq[Queue]): Map[TQM, PassengersMinute] =
    (for {
      queue <- queues
      minute <- minutes
    } yield TQM(T1, queue, minute) -> PassengersMinute(T1, queue, minute, Seq(1d), None)).toMap

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  "Port allocation dispatch" should {
    "await active authoritative work when shutting down a runtime with neutral recommendations and deployments" in {
      val localSystem = ActorSystem("allocation-runtime-shutdown")
      val localMaterializer = Materializer(localSystem)
      val events = new Events
      val configuration = settings.copy(
        neutral = Some(NeutralAllocationSettings(10.seconds, 10.seconds)),
        shutdownGrace = Some(5.seconds)
      )
      val runtime = new QueueAllocationRuntime(configuration, events)(localSystem, global, localMaterializer)
      val firstSla = Promise[Int]()
      val secondSla = Promise[Int]()
      val firstRequested = Promise[Unit]()
      val secondRequested = Promise[Unit]()
      val controlled: CalculationControl => TryCrunchWholePax = _ => maximum
      val firstCoordinator = runtime.coordinator(QueueAllocationKind.Recommendations, "LHR", controlled).get
      val secondCoordinator = runtime.coordinator(QueueAllocationKind.Deployments, "LHR", controlled).get
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> Vector.fill(30)(List(1d)))
      val workload = passengers.map { case (queue, values) => queue -> values.map(_.sum) }
      val capacities: Map[Queue, QueueCapacityProvider] =
        Map(EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(2)))
      val fixed = FixedTerminalDeskLimits(
        _ => Map.empty,
        capacities,
        (_, _) => Future.failed(new AssertionError("Must not query live counts"))
      )
      val firstProvider = TerminalDesksAndWaitsProvider(
        T1,
        (_, _) => { firstRequested.trySuccess(()); firstSla.future },
        List(EeaDesk),
        maximum,
        "recommendations"
      )
      val secondProvider = firstProvider.copy(
        sla = (_, _) => { secondRequested.trySuccess(()); secondSla.future },
        description = "deployments"
      )
      val first = firstCoordinator.calculate(
        firstProvider,
        T1,
        minutes,
        passengers,
        workload,
        fixed,
        manualDeskCapacity = Some(4)
      )
      val staffed = FlexedTerminalDeskLimitsFromAvailableStaff(
        List.fill(30)(2),
        4,
        Set(EeaDesk),
        _ => Map.empty,
        capacities,
        (_, _) => Future.failed(new AssertionError("Must not query live counts"))
      )
      val second = secondCoordinator.calculate(secondProvider, T1, minutes, passengers, workload, staffed)
      try {
        Await.result(firstRequested.future, 2.seconds)
        Await.result(secondRequested.future, 2.seconds)
        val shutdown = runtime.shutdown()
        shutdown.isCompleted shouldBe false
        firstSla.success(9)
        intercept[services.crunch.CalculationAborted](Await.result(first, 2.seconds)).reason shouldBe "shutdown"
        shutdown.isCompleted shouldBe false
        secondSla.success(9)
        intercept[services.crunch.CalculationAborted](Await.result(second, 2.seconds)).reason shouldBe "shutdown"
        Await.result(shutdown, 2.seconds)
        (runtime.shutdown() eq shutdown) shouldBe true
        events.names.asScala should not contain "queue_allocation_neutral_completed"
        events.names.asScala should not contain "queue_allocation_fallback_started"
        events.names.asScala should not contain "queue_allocation_cancellation_overrun"
        intercept[IllegalArgumentException] {
          runtime.coordinator(QueueAllocationKind.Recommendations, "LHR", controlled)
        }
      } finally {
        firstSla.trySuccess(9)
        secondSla.trySuccess(9)
        try Await.ready(runtime.shutdown(), 3.seconds)
        finally Await.result(localSystem.terminate(), 5.seconds)
      }
    }

    "retain the default legacy path and its existing passenger-count lookup" in {
      val reads = new AtomicInteger()
      val fixed = FixedTerminalDeskLimits(
        _ => Map.empty,
        Map(EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(1))),
        (_, _) => { reads.incrementAndGet(); Future.successful(IndexedSeq.fill(30)(1)) }
      )
      val output = Await.result(
        port(Seq(EeaDesk)).terminalLoadsToDesks(minutes, loads(Seq(EeaDesk)), fixed, "scenarios", T1),
        3.seconds
      )
      output.minutes.size shouldBe 30
      output.minutes.map(_.deskRec).toSet shouldBe Set(1)
      reads.get() shouldBe 1
    }

    "apply deployment SLAs and staff limits, include queues absent from priority and exclude Transfer" in {
      val slas = new ConcurrentLinkedQueue[Int]()
      val controlled: CalculationControl => TryCrunchWholePax = control =>
        (passengers, min, max, config) => {
          control.check()
          slas.add(config.sla)
          maximum(passengers, min, max, config)
        }
      val coordinator = new QueueAllocationCoordinator(
        QueueAllocationKind.Deployments,
        "LHR",
        QueueAllocationMode.Neutral,
        settings,
        QueueAllocationReporter.NoOp,
        controlled
      )
      val staffed = FlexedTerminalDeskLimitsFromAvailableStaff(
        List.fill(30)(1),
        4,
        Set(EeaDesk, NonEeaDesk),
        _ => Map.empty,
        Map(
          EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(4)),
          NonEeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(4))
        ),
        (_, _) => Future.failed(new AssertionError("Neutral must not query passenger counts"))
      )
      val output = Await.result(
        port(Seq(EeaDesk, NonEeaDesk, Transfer), Some(coordinator))
          .loadsToSimulations(minutes, loads(Seq(EeaDesk, NonEeaDesk, Transfer)), staffed, "deployments", T1),
        3.seconds
      ).minutes
      output.size shouldBe 60
      output.map(_.queue).toSet shouldBe Set(EeaDesk, NonEeaDesk)
      output.groupBy(_.minute).values.foreach(_.map(_.desks).sum shouldBe 1)
      slas.asScala.toSet shouldBe Set(9)
      coordinator.shutdown()
    }

    "return no mode coordinator for default legacy runtime configuration" in {
      val events = new Events
      val runtime = new QueueAllocationRuntime(QueueAllocationSettings.legacy, events)
      val controlled: CalculationControl => TryCrunchWholePax = _ => maximum
      runtime.coordinator(QueueAllocationKind.Recommendations, "LHR", controlled) shouldBe None
      runtime.coordinator(QueueAllocationKind.Deployments, "LHR", controlled) shouldBe None
      events.names.asScala.toSeq shouldBe Seq.fill(2)("queue_allocation_configured")
      Await.result(runtime.shutdown(), 2.seconds)
    }
  }
}
