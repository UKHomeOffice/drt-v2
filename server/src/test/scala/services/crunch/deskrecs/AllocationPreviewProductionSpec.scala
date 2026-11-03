package services.crunch.deskrecs

import com.typesafe.config.ConfigFactory
import drt.shared.AllocationPreview
import drt.shared.CrunchApi.{ PassengersMinute, StaffMinute }
import org.apache.pekko.actor.{ ActorSystem, CoordinatedShutdown, Scheduler }
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import services.OptimiserWithFlexibleProcessors
import services.crunch.{ CalculationAborted, TestDefaults }
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, PortDeskLimits }
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.egates.{ EgateBank, EgateBanksUpdate, EgateBanksUpdates }
import uk.gov.homeoffice.drt.models.{ CrunchMinute, TQM }
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }
import uk.gov.homeoffice.drt.ports.Terminals.{ T1, Terminal }
import uk.gov.homeoffice.drt.time.{ LocalDate, SDate, SDateLike }

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.immutable.{ NumericRange, SortedMap }
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._

class AllocationPreviewProductionSpec extends AnyWordSpec with Matchers with Eventually with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("preview-production-parity")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val mat: Materializer = Materializer(system)
  private implicit val scheduler: Scheduler = system.scheduler
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(90, Seconds), Span(20, Millis))
  private val queues: Seq[Queue] = Seq(EGate, EeaDesk, NonEeaDesk)
  private val airport: AirportConfig = TestDefaults.airportConfig.copy(
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap[Terminal, Seq[Queue]](T1 -> queues)),
    minMaxDesksByTerminalQueue24Hrs = Map(T1 -> Map(
      EGate -> (List.fill(24)(1), List.fill(24)(1)),
      EeaDesk -> (List.fill(24)(0), List.fill(24)(3)),
      NonEeaDesk -> (List.fill(24)(2), List.fill(24)(3))
    )),
    desksByTerminal = Map(T1 -> 4),
    flexedQueues = Set(EeaDesk, NonEeaDesk),
    queuePriority = List(EeaDesk, NonEeaDesk, EGate),
    crunchOffsetMinutes = 37
  )
  private val config = Configuration(
    "env" -> "preprod",
    "feature-flags.enable-queue-allocation-preview" -> true,
    "crunch.deployments.sla" -> 8
  )
  private val recSla: (LocalDate, Queue) => Future[Int] = (_, _) => Future.successful(12)
  private val depSla: (LocalDate, Queue) => Future[Int] =
    (_, _) => Future.successful(config.get[Int]("crunch.deployments.sla"))

  private def recommendationLimits(flexed: Boolean): (
      Terminal => Future[EgateBanksUpdates],
      Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]]
  ) => Map[Terminal, ConfiguredTerminalDeskLimitsLike] = (egates, counts) =>
    if (flexed) PortDeskLimits.flexed(airport, egates, counts)(ec)
    else PortDeskLimits.fixed(airport, egates, counts)(ec)

  override protected def afterAll(): Unit =
    try Await.result(system.terminate(), 5.seconds)
    finally super.afterAll()

  "The production preview factory" should {
    Seq(("2026-03-29", 1380, false), ("2026-10-25", 1500, true)).foreach {
      case (day, length, deploymentFairXmax) =>
        s"match independent neutral providers on $day with offset, staffing gaps and heterogeneous banks" in {
          val date = SDate(day).toLocalDate
          val request = TerminalUpdateRequest(T1, date)
          val minutes: NumericRange[Long] = request.minutesInMillis(airport.crunchOffsetMinutes)
          minutes.size shouldBe length
          val first = request.start.addMinutes(airport.crunchOffsetMinutes).millisSinceEpoch
          val last = request.end.addMinutes(airport.crunchOffsetMinutes).millisSinceEpoch
          val burst = Set(0, 30, minutes.size / 2, minutes.size - 30)
          val loads: Map[TQM, PassengersMinute] = (for {
            queue <- queues
            index <- burst.toSeq
          } yield {
            val minute = minutes(index)
            val passengers = Vector.fill(if (queue == EeaDesk) 20 else 200)(if (queue == EeaDesk) 0d else 0.2d)
            TQM(T1, queue, minute) -> PassengersMinute(T1, queue, minute, passengers, None)
          }).toMap
          val missing = Set(minutes.head, minutes(30))
          val suppliedStaff = minutes.filterNot(missing).map(minute => StaffMinute(T1, minute, 7, 2, 0, None)).toVector
          val expectedStaff = minutes.map(minute =>
            suppliedStaff.find(_.minute == minute).getOrElse(StaffMinute(T1, minute, 0, 0, 0, None))
          ).toVector
          val banks = EgateBanksUpdates(List(
            EgateBanksUpdate(
              0L,
              Vector(
                EgateBank(Vector(true, false, true)),
                EgateBank(Vector(true, true, true, true, true))
              )
            ),
            EgateBanksUpdate(
              minutes(minutes.size / 2),
              Vector(
                EgateBank(Vector(false, true, true, true))
              )
            )
          ))
          val previewReads = new AtomicInteger()
          val staffReads = new AtomicInteger()
          val bankReads = new AtomicInteger()
          val recommendationSlaReads = new AtomicInteger()
          val deploymentSlaReads = new AtomicInteger()
          val selectedKind = new AtomicReference("recommended")
          val seenWindow = new AtomicReference(Vector.empty[(Long, Long)])
          val egates: Terminal => Future[EgateBanksUpdates] = _ => {
            bankReads.incrementAndGet()
            Future.successful(banks)
          }
          val counts: Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]] = _ =>
            (range, queue) => {
              Future.successful(range.map(minute => loads.get(TQM(T1, queue, minute)).fold(0)(_.passengers.size)))
            }
          val independentEgates: Terminal => Future[EgateBanksUpdates] = _ => Future.successful(banks)
          val preview = AllocationPreviewService.production(
            airport,
            config,
            (start, end, terminal) => {
              previewReads.incrementAndGet()
              seenWindow.set(seenWindow.get() :+ (start.millisSinceEpoch -> end.millisSinceEpoch))
              require(terminal == T1 && start.millisSinceEpoch == first && end.millisSinceEpoch == last)
              Future.successful(loads)
            },
            (start, end, terminal) => {
              staffReads.incrementAndGet()
              require(terminal == T1 && start.millisSinceEpoch == first && end.millisSinceEpoch == last)
              Future.successful(suppliedStaff)
            },
            egates,
            recommendationLimits(flexed = deploymentFairXmax),
            (day, queue) => {
              recommendationSlaReads.incrementAndGet()
              require(selectedKind.get() == "recommended", "Unselected recommendations must not run")
              recSla(day, queue)
            },
            (day, queue) => {
              deploymentSlaReads.incrementAndGet()
              require(selectedKind.get() == "available", "Unselected deployments must not run")
              depSla(day, queue)
            },
            deploymentFairXmax
          )
          val settings = QueueAllocationSettings(
            recommendations = QueueAllocationMode.Neutral,
            deployments = QueueAllocationMode.Neutral,
            neutral = Some(NeutralAllocationSettings(2.minutes, 2.minutes)),
            cancelGrace = Some(1.second),
            shutdownGrace = Some(3.seconds)
          )
          val expectedEvents = new AtomicReference(Vector.empty[String])
          val reporter = new QueueAllocationReporter {
            override def event(
                context: QueueAllocationContext,
                name: String,
                outcome: String,
                fields: Map[String, String]
            ): Unit = expectedEvents.set(expectedEvents.get() :+ name)
            override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()
          }
          val runtime = new QueueAllocationRuntime(settings, reporter)
          def expected(kind: String): Seq[CrunchMinute] = {
            val recommended = kind == "recommended"
            val useFairXmax = recommended || deploymentFairXmax
            val coordinator = runtime.coordinator(
              if (recommended) QueueAllocationKind.Recommendations else QueueAllocationKind.Deployments,
              airport.portCode.iata,
              cruncher = control => OptimiserWithFlexibleProcessors.crunchWholePaxControlled(useFairXmax, control)
            )
            val provider = PortDesksAndWaitsProvider(
              airport,
              OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax),
              FlightFilter.forPortConfig(airport),
              TestDefaults.paxFeedSourceOrder,
              if (recommended) recSla else depSla,
              identity,
              coordinator
            )
            if (recommended)
              Await.result(
                provider.terminalLoadsToDesks(
                  minutes,
                  loads,
                  PortDeskLimits.fixed(airport, independentEgates, counts)(ec)(T1),
                  "independent-production-recommendations",
                  T1
                ),
                2.minutes
              ).minutes.map(_.toMinute).toSeq
            else {
              val independentLimits = PortDeskLimits.flexedByAvailableStaff(airport, independentEgates, counts)(
                T1,
                expectedStaff.map(_.availableAtPcp).toList
              )
              Await.result(
                provider.loadsToSimulations(
                  minutes,
                  loads,
                  independentLimits,
                  "independent-production-deployments",
                  T1
                ),
                2.minutes
              ).minutes.map { row =>
                val pax = loads.get(TQM(T1, row.queue, row.minute)).map(_.passengers).getOrElse(Seq.empty)
                row.toMinute.copy(paxLoad = pax.size, workLoad = pax.sum)
              }.toSeq
            }
          }
          try {
            Seq("recommended", "available").zipWithIndex.foreach { case (kind, index) =>
              selectedKind.set(kind)
              recommendationSlaReads.set(0)
              deploymentSlaReads.set(0)
              val response = Await.result(preview.calculate(AllocationPreview.Request(T1, date, kind)), 2.minutes)
              response.startMillis shouldBe first
              response.endMillis shouldBe last + 60000L
              val result = if (kind == "recommended") {
                response.available shouldBe None
                recommendationSlaReads.get() should be > 0
                deploymentSlaReads.get() shouldBe 0
                response.recommended.get
              } else {
                response.recommended shouldBe None
                deploymentSlaReads.get() should be > 0
                recommendationSlaReads.get() shouldBe 0
                response.available.get
              }
              previewReads.get() shouldBe index + 1
              staffReads.get() shouldBe index + 1
              bankReads.get() shouldBe index + 1
              val independent = expected(kind)
              result.queues.toSet shouldBe queues.toSet
              result.minutes.map(row => row.key -> row).toMap shouldBe independent.map(row => row.key -> row).toMap
              result.staffMinutes shouldBe expectedStaff
              result.slas shouldBe queues.map(_ -> (if (kind == "recommended") 12 else 8)).toMap
              result.measurements.isDefined shouldBe true
              val measurements = result.measurements.get
              measurements.neutral.byQueue.keySet shouldBe queues.toSet
              measurements.neutral.total.isDefined shouldBe true
              measurements.neutral.unavailableQueues shouldBe empty
              measurements.neutral.resourceFeasible shouldBe true
              if (kind == "available") {
                val available = expectedStaff.map(row => row.minute -> row.availableAtPcp).toMap
                result.minutes.groupBy(_.minute).foreach { case (minute, rows) =>
                  rows.map(_.deployedDesks.get).sum should be <= available(minute)
                }
              }
              result.minutes.filter(row => missing(row.minute)).foreach { row =>
                if (kind == "available") row.deployedDesks shouldBe Some(0)
              }
              result.minutes.filter(row => row.queue == EeaDesk && row.minute == minutes(90)).foreach { row =>
                if (kind == "recommended") row.deskRec shouldBe 0 else row.deployedDesks shouldBe Some(0)
              }
              if (kind == "recommended")
                result.minutes.filter(row => row.queue == EeaDesk && row.minute == minutes(30))
                  .foreach(_.deskRec should be >= 1)
              eventually { preview.activeWork shouldBe false }
            }
            previewReads.get() shouldBe 2
            staffReads.get() shouldBe 2
            bankReads.get() shouldBe 2
            seenWindow.get() shouldBe Vector.fill(2)(first -> last)
            expectedEvents.get().count(_ == "queue_allocation_neutral_completed") shouldBe 2
            expectedEvents.get() should not contain "queue_allocation_fallback_started"
          } finally {
            Await.result(preview.shutdown(), 5.seconds)
            Await.result(runtime.shutdown(), 5.seconds)
          }
        }
    }

    "register real coordinated shutdown with both operational modes legacy and retain unresolved acquisition until true termination" in {
      val localConfig = ConfigFactory.parseString("""
        pekko.coordinated-shutdown.terminate-actor-system = off
        pekko.coordinated-shutdown.run-by-actor-system-terminate = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """).withFallback(ConfigFactory.load())
      val localSystem = ActorSystem("preview-production-all-legacy-shutdown", localConfig)
      val localMat = Materializer(localSystem)
      val pending = Promise[Map[TQM, PassengersMinute]]()
      val requested = Promise[Unit]()
      val local = config ++ Configuration(
        "crunch.queue-allocation.preview.cancel-grace" -> "20 milliseconds",
        "crunch.queue-allocation.preview.shutdown-grace" -> "50 milliseconds"
      )
      QueueAllocationSettings.fromConfiguration(local).recommendations shouldBe QueueAllocationMode.Legacy
      QueueAllocationSettings.fromConfiguration(local).deployments shouldBe QueueAllocationMode.Legacy
      val egates: Terminal => Future[EgateBanksUpdates] = _ => Future.successful(EgateBanksUpdates(List()))
      val preview = AllocationPreviewService.production(
        airport,
        local,
        (_, _, _) => { requested.trySuccess(()); pending.future },
        (_, _, _) => Future.successful(Vector.empty),
        egates,
        recommendationLimits(flexed = false),
        recSla,
        depSla,
        false
      )(localSystem, localSystem.dispatcher, localMat)
      val request = AllocationPreview.Request(T1, SDate("2026-10-30").toLocalDate)
      val calculation = preview.calculate(request)
      try {
        Await.result(requested.future, 5.seconds)
        Await.result(CoordinatedShutdown(localSystem).run(CoordinatedShutdown.UnknownReason), 5.seconds)
        preview.activeWork shouldBe true
        preview.workerStopped shouldBe false
        pending.isCompleted shouldBe false
        Await.result(preview.calculate(request).failed, 5.seconds) shouldBe AllocationPreviewService.Stopping
        pending.success(Map.empty)
        Await.result(calculation.failed, 5.seconds) shouldBe a[CalculationAborted]
        eventually {
          preview.activeWork shouldBe false
          preview.workerStopped shouldBe true
        }
      } finally {
        pending.trySuccess(Map.empty)
        Await.result(localSystem.terminate(), 5.seconds)
      }
    }
  }
}
