package services.crunch.deskrecs

import drt.shared.CrunchApi.{ DeskRecMinute, MinutesContainer, PassengersMinute }
import drt.shared.SimulationMinute
import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorSystem, Scheduler }
import org.apache.pekko.stream.{ Materializer, OverflowStrategy, QueueOfferResult }
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source, SourceQueueWithComplete }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.{ OptimizerCrunchResult, TryCrunchWholePax }
import services.crunch.{ CalculationControl, TestDefaults }
import services.crunch.desklimits.{ DeskCapacityProvider, QueueCapacityProvider, TerminalDeskLimitsLike }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{ FlexedTerminalDeskLimits, FlexedTerminalDeskLimitsFromAvailableStaff }
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.models.{ CrunchMinute, TQM }
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{ EeaDesk, Queue }
import uk.gov.homeoffice.drt.ports.Terminals.{ T1, Terminal }
import uk.gov.homeoffice.drt.time.{ LocalDate, SDate }

import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.collection.immutable.{ NumericRange, SortedMap }
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

class QueueAllocationPublicationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private type Publication = MinutesContainer[CrunchMinute, TQM]

  private implicit val system: ActorSystem = ActorSystem("queue-allocation-publication-test")
  private implicit val scheduler: Scheduler = system.scheduler
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val airportConfig: AirportConfig = TestDefaults.airportConfig.copy(
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap[Terminal, Seq[Queue]](T1 -> Seq(EeaDesk))),
    minMaxDesksByTerminalQueue24Hrs = Map(T1 -> Map(EeaDesk -> (List.fill(24)(1), List.fill(24)(3)))),
    desksByTerminal = Map(T1 -> 3)
  )
  private val request = TerminalUpdateRequest(T1, SDate("2021-05-01").toLocalDate)
  private val minutes: NumericRange[Long] = request.minutesInMillis(airportConfig.crunchOffsetMinutes)
  private val passengers = Vector(0.25d, 0.5d)
  private val loads: Map[TQM, PassengersMinute] = minutes.map { minute =>
    TQM(T1, EeaDesk, minute) -> PassengersMinute(T1, EeaDesk, minute, passengers, None)
  }.toMap

  private class Events extends QueueAllocationReporter {
    private val entries = new ConcurrentLinkedQueue[String]()
    private val signals = new ConcurrentHashMap[String, Promise[Unit]]()

    private def signal(name: String): Promise[Unit] = signals.computeIfAbsent(name, _ => Promise[Unit]())

    override def event(
        context: QueueAllocationContext,
        name: String,
        outcome: String,
        fields: Map[String, String]
    ): Unit = {
      entries.add(name)
      signal(name).trySuccess(())
      ()
    }

    override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()

    def await(name: String): Unit = Await.result(signal(name).future, 5.seconds)

    def names: Seq[String] = entries.asScala.toVector
  }

  private def fixed(slots: Int, wait: Int = 0, queued: Double = 0d): TryCrunchWholePax =
    (pax, _, _, _) =>
      Success(OptimizerCrunchResult(
        IndexedSeq.fill(pax.size)(slots),
        Seq.fill(pax.size)(wait),
        IndexedSeq.fill(pax.size)(queued)
      ))

  private def settings(kind: QueueAllocationKind, mode: QueueAllocationMode): QueueAllocationSettings =
    QueueAllocationSettings(
      recommendations = if (kind == QueueAllocationKind.Recommendations) mode else QueueAllocationMode.Legacy,
      deployments = if (kind == QueueAllocationKind.Deployments) mode else QueueAllocationMode.Legacy,
      neutral = Some(NeutralAllocationSettings(5.seconds, 5.seconds)),
      cancelGrace = Some(1.second),
      shutdownGrace = Some(2.seconds)
    )

  private class Graph(
      kind: QueueAllocationKind,
      mode: QueueAllocationMode,
      controlled: CalculationControl => TryCrunchWholePax,
      val events: Events,
      fixedRecommendations: Boolean
  ) {
    private val configuration = settings(kind, mode)
    private val coordinator = new QueueAllocationCoordinator(
      kind,
      airportConfig.portCode.iata,
      mode,
      configuration,
      events,
      controlled,
      demandPlannerFactory = QueueDemandPlannerTestSupport.proposals
    )
    val legacyCalls: AtomicInteger = new AtomicInteger()
    val staffReads: AtomicInteger = new AtomicInteger()
    private val legacy: TryCrunchWholePax = (pax, min, max, config) => {
      legacyCalls.incrementAndGet()
      fixed(1, wait = 7, queued = 3d)(pax, min, max, config)
    }
    private val provider = PortDesksAndWaitsProvider(
      airportConfig,
      legacy,
      FlightFilter.forPortConfig(airportConfig),
      TestDefaults.paxFeedSourceOrder,
      (_, queue) => Future.successful(airportConfig.slaByQueue(queue)),
      identity,
      allocationCoordinator = Option.when(mode != QueueAllocationMode.Legacy)(coordinator)
    )
    private val minimums: LocalDate => Map[Queue, IndexedSeq[Int]] =
      _ => Map(EeaDesk -> IndexedSeq.fill(24)(1))
    private val counts: (NumericRange[Long], Queue) => Future[Seq[Int]] =
      (range, _) =>
        if (mode == QueueAllocationMode.Legacy) Future.successful(range.map(_ => passengers.size))
        else Future.failed(new AssertionError("Captured workload must supply passenger counts"))
    private val capacities: Map[Queue, QueueCapacityProvider] =
      Map(EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(3)))
    private val recommendationLimits: TerminalDeskLimitsLike =
      if (fixedRecommendations) FixedTerminalDeskLimits(minimums, capacities, counts)
      else FlexedTerminalDeskLimits(3, Set(EeaDesk), minimums, capacities, counts)
    private val updates = new ConcurrentLinkedQueue[(Terminal, LocalDate, Long)]()
    private val received = new ConcurrentLinkedQueue[Publication]()
    private val previous = new AtomicReference[Option[Publication]](None)
    val firstPublication: Promise[Publication] = Promise[Publication]()
    private val updatedAt: (Terminal, LocalDate, Long) => Future[Done] = (terminal, date, timestamp) => {
      updates.add((terminal, date, timestamp))
      Future.successful(Done)
    }
    private val flow = kind match {
      case QueueAllocationKind.Recommendations =>
        DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
          loadsProvider = (_, _, _) => Future.successful(loads),
          maxDesksProviders = Map(T1 -> recommendationLimits),
          loadsToQueueMinutes = provider.terminalLoadsToDesks,
          setUpdatedAtForDay = updatedAt
        )
      case QueueAllocationKind.Deployments =>
        DynamicRunnableDeployments.crunchRequestsToDeployments(
          loadsProvider = (_, _, _) => Future.successful(loads),
          staffMinutesProvider = (_, _, _) => {
            staffReads.incrementAndGet()
            Future.successful(List.fill(minutes.size)(2))
          },
          staffToDeskLimits = (_, staff) =>
            FlexedTerminalDeskLimitsFromAvailableStaff(staff, 3, Set(EeaDesk), minimums, capacities, counts),
          loadsToQueueMinutes = provider.loadsToSimulations,
          setUpdatedAtForDay = updatedAt
        )
    }
    private val materialized: (SourceQueueWithComplete[TerminalUpdateRequest], Future[Seq[Publication]]) =
      Source.queue[TerminalUpdateRequest](2, OverflowStrategy.backpressure)
        .via(flow)
        .map { publication =>
          received.add(publication)
          previous.set(Some(publication))
          firstPublication.trySuccess(publication)
          publication
        }
        .toMat(Sink.seq)(Keep.both)
        .run()

    def offer(): Unit =
      Await.result(materialized._1.offer(request), 5.seconds) shouldBe QueueOfferResult.Enqueued

    def publications: Seq[Publication] = received.asScala.toVector

    def progress: Seq[(Terminal, LocalDate, Long)] = updates.asScala.toVector

    def recordedState: Option[Publication] = previous.get()

    def finish(): Seq[Publication] = {
      materialized._1.complete()
      Await.result(materialized._2, 5.seconds)
    }

    def close(): Unit = {
      coordinator.shutdown()
      materialized._1.complete()
      Await.ready(materialized._2, 5.seconds)
      ()
    }
  }

  private def withGraph(
      kind: QueueAllocationKind,
      mode: QueueAllocationMode,
      controlled: CalculationControl => TryCrunchWholePax,
      fixedRecommendations: Boolean = false
  )(test: Graph => Unit): Unit = {
    val graph = new Graph(kind, mode, controlled, new Events, fixedRecommendations)
    try test(graph)
    finally graph.close()
  }

  private def assertOutput(
      kind: QueueAllocationKind,
      publication: Publication,
      desks: Int,
      wait: Int,
      queued: Int
  ): Unit = {
    publication.minutes.size shouldBe minutes.size
    publication.minutes.map(_.key).toSet shouldBe minutes.map(minute => TQM(T1, EeaDesk, minute)).toSet
    kind match {
      case QueueAllocationKind.Recommendations =>
        publication.minutes.foreach { minute =>
          minute shouldBe DeskRecMinute(
            T1,
            EeaDesk,
            minute.minute,
            passengers.size,
            passengers.sum,
            desks,
            wait,
            Some(queued)
          )
        }
      case QueueAllocationKind.Deployments =>
        publication.minutes.foreach { minute =>
          minute shouldBe SimulationMinute(T1, EeaDesk, minute.minute, desks, wait, Some(queued))
          minute.toMinute.deployedDesks shouldBe Some(desks)
          minute.toMinute.deployedWait shouldBe Some(wait)
          minute.toMinute.maybeDeployedPaxInQueue shouldBe Some(queued)
        }
    }
  }

  private def assertOneProgressUpdate(graph: Graph): Unit = {
    graph.progress.size shouldBe 1
    graph.progress.head._1 shouldBe T1
    graph.progress.head._2 shouldBe request.date
    graph.progress.head._3 should be > 0L
  }

  override protected def afterAll(): Unit =
    try {
      Await.result(system.terminate(), 5.seconds)
    } finally {
      super.afterAll()
    }

  Seq(QueueAllocationKind.Recommendations, QueueAllocationKind.Deployments).foreach { kind =>
    s"The ${kind.name} publication flow" should {
      "preserve legacy output and progress without running the balanced optimiser" in {
        val controlledCalls = new AtomicInteger()
        val controlled: CalculationControl => TryCrunchWholePax = _ => {
          controlledCalls.incrementAndGet()
          (_, _, _, _) => Failure(new AssertionError("Legacy mode must not run balanced allocation"))
        }

        withGraph(kind, QueueAllocationMode.Legacy, controlled) { graph =>
          graph.offer()
          val output = graph.finish()
          output.size shouldBe 1
          assertOutput(kind, output.head, desks = 1, wait = 7, queued = 3)
          assertOneProgressUpdate(graph)
          graph.recordedState shouldBe Some(output.head)
          graph.legacyCalls.get() shouldBe 1
          controlledCalls.get() shouldBe 0
          graph.events.names shouldBe empty
        }
      }

      "publish one complete neutral result and advance progress once without running legacy" in {
        withGraph(kind, QueueAllocationMode.Neutral, _ => fixed(2)) { graph =>
          graph.offer()
          val output = graph.finish()
          output.size shouldBe 1
          assertOutput(kind, output.head, desks = 2, wait = 0, queued = 0)
          assertOneProgressUpdate(graph)
          graph.recordedState shouldBe Some(output.head)
          graph.legacyCalls.get() shouldBe 0
          graph.staffReads.get() shouldBe (if (kind == QueueAllocationKind.Deployments) 1 else 0)
          graph.events.names.count(_ == "queue_allocation_neutral_completed") shouldBe 1
          graph.events.names should not contain "queue_allocation_fallback_started"
        }
      }

      "publish one validated legacy fallback and advance progress only once" in {
        val attempts = new AtomicInteger()
        val controlled: CalculationControl => TryCrunchWholePax = _ =>
          if (attempts.incrementAndGet() == 1)
            (_, _, _, _) => Failure(new IllegalStateException("neutral optimiser failed"))
          else fixed(1, wait = 7, queued = 3d)

        withGraph(kind, QueueAllocationMode.Neutral, controlled) { graph =>
          graph.offer()
          val output = graph.finish()
          output.size shouldBe 1
          assertOutput(kind, output.head, desks = 1, wait = 7, queued = 3)
          assertOneProgressUpdate(graph)
          attempts.get() shouldBe 2
          graph.events.names.count(_ == "queue_allocation_fallback_started") shouldBe 1
          graph.events.names.count(_ == "queue_allocation_fallback_completed") shouldBe 1
          graph.events.names should not contain "queue_allocation_neutral_completed"
        }
      }

      "retain the previous recorded state with no output or progress update when neutral and fallback both fail" in {
        val fail = new AtomicBoolean(false)
        val failedAttempts = new AtomicInteger()
        val controlled: CalculationControl => TryCrunchWholePax = _ =>
          if (fail.get()) {
            failedAttempts.incrementAndGet()
            (_, _, _, _) => Failure(new IllegalStateException("authoritative optimiser failed"))
          } else fixed(2)

        withGraph(kind, QueueAllocationMode.Neutral, controlled) { graph =>
          graph.offer()
          val first = Await.result(graph.firstPublication.future, 5.seconds)
          assertOutput(kind, first, desks = 2, wait = 0, queued = 0)
          val previousState = graph.recordedState
          val previousProgress = graph.progress
          fail.set(true)

          graph.offer()
          graph.events.await("queue_allocation_request_failed")
          graph.finish() shouldBe Seq(first)
          graph.publications shouldBe Seq(first)
          graph.recordedState shouldBe previousState
          graph.progress shouldBe previousProgress
          assertOneProgressUpdate(graph)
          failedAttempts.get() shouldBe 2
          graph.events.names.count(_ == "queue_allocation_neutral_completed") shouldBe 1
          graph.events.names.count(_ == "queue_allocation_fallback_started") shouldBe 1
          graph.events.names should not contain "queue_allocation_fallback_completed"
        }
      }
    }
  }

  "The fixed recommendations publication flow" should {
    "publish complete balanced recommendation rows without available staff and advance progress once" in {
      withGraph(
        QueueAllocationKind.Recommendations,
        QueueAllocationMode.Neutral,
        _ => fixed(2),
        fixedRecommendations = true
      ) { graph =>
        graph.offer()
        val output = graph.finish()
        output.size shouldBe 1
        assertOutput(QueueAllocationKind.Recommendations, output.head, desks = 2, wait = 0, queued = 0)
        assertOneProgressUpdate(graph)
        graph.recordedState shouldBe Some(output.head)
        graph.staffReads.get() shouldBe 0
        graph.legacyCalls.get() shouldBe 0
        graph.events.names.count(_ == "queue_allocation_neutral_completed") shouldBe 1
        graph.events.names should not contain "queue_allocation_fallback_started"
        graph.events.names should not contain "queue_allocation_ineligible"
      }
    }
  }
}
