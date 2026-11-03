package services.crunch.deskrecs

import drt.shared.AllocationPreview
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationExecutionContext }
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._

class AllocationPreviewServiceSpec extends AnyWordSpec with Matchers with Eventually with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("neutral-service-spec")
  private implicit val ec: ExecutionContext = system.dispatcher
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(10, Millis))
  private val request = AllocationPreview.Request(T1, SDate("2026-10-30").toLocalDate)
  private val result = AllocationPreview.Result(Seq.empty, Seq.empty, Seq.empty, Map.empty)
  private val response = AllocationPreview.Response(0L, 60000L, Some(result), None)

  private def create(
      work: (AllocationPreview.Request, CalculationControl, ExecutionContext) => Future[AllocationPreview.Response],
      timers: ControllableAllocationScheduler = new ControllableAllocationScheduler
  ): AllocationPreviewService = new AllocationPreviewService(AllocationPreviewSettings(), work)(timers, ec)

  override protected def afterAll(): Unit =
    try Await.result(system.terminate(), 5.seconds)
    finally super.afterAll()

  "A neutral calculation" should {
    "return one asynchronous response, reject overlap, and admit the next request after termination" in {
      val held = Promise[AllocationPreview.Response]()
      val entered = Promise[Thread]()
      val caller = Thread.currentThread()
      val service = create((input, _, _) => {
        input shouldBe request
        entered.trySuccess(Thread.currentThread())
        held.future
      })
      try {
        val first = service.calculate(request)
        Await.result(entered.future, 5.seconds) should not be caller
        first.isCompleted shouldBe false
        service.activeWork shouldBe true
        Await.result(service.calculate(request).failed, 5.seconds) shouldBe AllocationPreviewService.Busy
        held.success(response)
        Await.result(first, 5.seconds) shouldBe response
        eventually { service.activeWork shouldBe false }
        Await.result(service.calculate(request), 5.seconds) shouldBe response
      } finally {
        held.trySuccess(response)
        Await.result(service.shutdown(), 5.seconds)
      }
      eventually { service.workerStopped shouldBe true }
    }

    "keep admission held after caller timeout until the actual tracked input settles" in {
      val timers = new ControllableAllocationScheduler
      val held = Promise[Unit]()
      val entered = Promise[Unit]()
      val service = create(
        (_, _, executor) => {
          CalculationExecutionContext.trackInput(held.future)(executor)
          entered.success(())
          Future.failed(new IllegalStateException("input failed"))
        },
        timers
      )
      try {
        val calculation = service.calculate(request)
        Await.result(entered.future, 5.seconds)
        timers.fire(2.minutes)
        eventually { timers.pending(1.second) shouldBe 1 }
        timers.fire(1.second)
        Await.result(calculation.failed, 5.seconds) shouldBe a[CalculationAborted]
        service.activeWork shouldBe true
        Await.result(service.calculate(request).failed, 5.seconds) shouldBe AllocationPreviewService.Busy
        held.success(())
        eventually { service.activeWork shouldBe false }
      } finally {
        held.trySuccess(())
        Await.result(service.shutdown(), 5.seconds)
      }
    }

    "close admission during shutdown without releasing an unresolved input at the shutdown deadline" in {
      val timers = new ControllableAllocationScheduler
      val held = Promise[AllocationPreview.Response]()
      val entered = Promise[Unit]()
      val service = create(
        (_, _, executor) => {
          entered.success(())
          CalculationExecutionContext.trackInput(held.future)(executor)
        },
        timers
      )
      try {
        val calculation = service.calculate(request)
        Await.result(entered.future, 5.seconds)
        val shutdown = service.shutdown()
        service.shutdown() should be theSameInstanceAs shutdown
        Await.result(service.calculate(request).failed, 5.seconds) shouldBe AllocationPreviewService.Stopping
        timers.fire(3.seconds)
        Await.result(shutdown, 5.seconds) shouldBe Done
        service.activeWork shouldBe true
        service.workerStopped shouldBe false
        held.success(response)
        Await.result(calculation.failed, 5.seconds) shouldBe a[CalculationAborted]
        eventually {
          service.activeWork shouldBe false
          service.workerStopped shouldBe true
        }
      } finally {
        held.trySuccess(response)
        Await.result(service.shutdown(), 5.seconds)
      }
    }

    "cancel a control installed after shutdown has already started" in {
      val serviceRef = new AtomicReference[AllocationPreviewService]()
      val shutdown = Promise[Future[Done]]()
      val timers = new ControllableAllocationScheduler(attempt =>
        if (attempt == 1) shutdown.success(serviceRef.get().shutdown())
      )
      val service = create((_, _, _) => fail("Shutdown must prevent input acquisition"), timers)
      serviceRef.set(service)
      val calculation = service.calculate(request)
      Await.result(calculation.failed, 5.seconds) shouldBe a[CalculationAborted]
      Await.result(Await.result(shutdown.future, 5.seconds), 5.seconds) shouldBe Done
      service.shutdown() should be theSameInstanceAs Await.result(shutdown.future, 5.seconds)
      eventually { service.workerStopped shouldBe true }
    }
  }
}
