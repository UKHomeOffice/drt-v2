package services.crunch.deskrecs

import com.typesafe.config.ConfigFactory
import controllers.application.QueueAllocationPreviewController
import drt.shared.{ AllocationPreview, ErrorResponse }
import drt.shared.CrunchApi.{ DeskRecMinute, StaffMinute }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{ Handler, Headers, RequestHeader, Session }
import play.api.routing.Router
import play.api.test.{ FakeRequest, Helpers }
import play.api.test.Helpers._
import play.filters.csrf.CSRF
import services.crunch.{ CalculationAborted, TestDefaults }
import uk.gov.homeoffice.drt.AppEnvironment.PreProdEnv
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.service.ApplicationService
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{ read, write }

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._

class AllocationPreviewControllerSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("neutral-controller-spec")
  private implicit val ec: ExecutionContext = system.dispatcher
  private val input = AllocationPreview.Request(T1, SDate("2026-10-30").toLocalDate)
  private val result = AllocationPreview.Result(
    Seq(EeaDesk),
    Seq(DeskRecMinute(T1, EeaDesk, 0L, 1d, 1d, 1, 0, Some(0)).toMinute),
    Seq(StaffMinute(T1, 0L, 2, 0, 0, None)),
    Map(EeaDesk -> 12)
  )
  private val response = AllocationPreview.Response(0L, 60000L, Some(result), None)

  private def withController(work: () => Future[AllocationPreview.Response])(
      test: (QueueAllocationPreviewController, AllocationPreviewService, AtomicInteger) => Unit
  ): Unit = {
    val calls = new AtomicInteger()
    val service = new AllocationPreviewService(
      AllocationPreviewSettings(),
      (_, _, _) => { calls.incrementAndGet(); work() }
    )(system.scheduler, ec)
    val ctrl = mock[uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface]
    val app = mock[ApplicationService]
    when(ctrl.system).thenReturn(system)
    when(ctrl.ec).thenReturn(ec)
    when(ctrl.timeout).thenReturn(Timeout(5.seconds))
    when(ctrl.airportConfig).thenReturn(TestDefaults.airportConfig)
    when(ctrl.config).thenReturn(Configuration("feature-flags.enable-queue-allocation-preview" -> true))
    when(ctrl.env).thenReturn(PreProdEnv)
    when(ctrl.applicationService).thenReturn(app)
    when(app.allocationPreviewService).thenReturn(Some(service))
    when(ctrl.getLoggedInUser(any[Configuration], any[Headers], any[Session])).thenReturn(
      LoggedInUser(
        "preview",
        "owner",
        "",
        Set(AccessOnlyPreprod, Debug, DesksAndQueuesView, TestDefaults.airportConfig.role)
      )
    )
    try test(new QueueAllocationPreviewController(Helpers.stubControllerComponents(), ctrl), service, calls)
    finally Await.result(service.shutdown(), 5.seconds)
  }

  override protected def afterAll(): Unit =
    try Await.result(system.terminate(), 5.seconds)
    finally super.afterAll()

  "The protected neutral POST" should {
    "wait for its single calculation and return only the selected result with HTTP 200" in {
      val held = Promise[AllocationPreview.Response]()
      val entered = Promise[Unit]()
      withController(() => { entered.trySuccess(()); held.future }) { (controller, _, calls) =>
        try {
          val pending = controller.calculate.apply(FakeRequest().withBody(write(input)))
          Await.result(entered.future, 5.seconds)
          pending.isCompleted mustBe false
          calls.get() mustBe 1
          val busy = controller.calculate.apply(FakeRequest().withBody(write(input)))
          status(busy) mustBe TOO_MANY_REQUESTS
          read[ErrorResponse](contentAsString(busy)).message must not be empty
          calls.get() mustBe 1
          held.success(response)
          status(pending) mustBe OK
          read[AllocationPreview.Response](contentAsString(pending)) mustBe response
        } finally held.trySuccess(response)
      }
    }

    "reject malformed bodies and invalid kinds without calculating" in {
      withController(() => Future.successful(response)) { (controller, _, calls) =>
        status(controller.calculate.apply(FakeRequest().withBody("not-json"))) mustBe BAD_REQUEST
        Seq("", "legacy", "both").foreach { kind =>
          status(controller.calculate.apply(FakeRequest().withBody(write(input.copy(kind = kind))))) mustBe BAD_REQUEST
        }
        calls.get() mustBe 0
      }
    }

    "accept the default recommended request and an explicitly selected available request" in {
      val withoutKind = ujson.read(write(input))
      withoutKind.obj.remove("kind")
      Seq(
        ujson.write(withoutKind) -> response,
        write(input.copy(kind = "available")) -> response.copy(recommended = None, available = Some(result))
      ).foreach { case (body, selectedResponse) =>
        withController(() => Future.successful(selectedResponse)) { (controller, _, calls) =>
          val completed = controller.calculate.apply(FakeRequest().withBody(body))
          status(completed) mustBe OK
          read[AllocationPreview.Response](contentAsString(completed)) mustBe selectedResponse
          calls.get() mustBe 1
        }
      }
    }

    "return explicit errors for invalid inputs, timeouts and failures" in {
      Seq(
        new IllegalArgumentException("invalid") -> BAD_REQUEST,
        CalculationAborted("expired") -> GATEWAY_TIMEOUT,
        new IllegalStateException("private detail") -> INTERNAL_SERVER_ERROR
      ).foreach { case (error, expectedStatus) =>
        withController(() => Future.failed(error)) { (controller, _, calls) =>
          val failed = controller.calculate.apply(FakeRequest().withBody(write(input)))
          status(failed) mustBe expectedStatus
          read[ErrorResponse](contentAsString(failed)).message must not be empty
          contentAsString(failed) must not include "private detail"
          calls.get() mustBe 1
        }
      }
    }

    "return unavailable after shutdown without acquiring inputs" in {
      withController(() => Future.successful(response)) { (controller, service, calls) =>
        Await.result(service.shutdown(), 5.seconds)
        val stopped = controller.calculate.apply(FakeRequest().withBody(write(input)))
        status(stopped) mustBe SERVICE_UNAVAILABLE
        read[ErrorResponse](contentAsString(stopped)).message must not be empty
        calls.get() mustBe 0
      }
    }

    "keep CSRF protection on the routed POST, accepting only a valid token" in {
      withController(() => Future.successful(response)) { (controller, _, calls) =>
        val router = new AllocationPreviewCsrfTestRouter(Router.from {
          case request if request.method == "POST" && request.path == "/queue-allocation/neutral" =>
            controller.calculate
          case request if request.method == "GET" && request.path == "/preview-csrf-token" =>
            controller.Action { request =>
              controller.Ok(CSRF.getToken(request).map(_.value).getOrElse("missing")).as("text/html")
            }
        })
        val app = new GuiceApplicationBuilder()
          .loadConfig(Configuration(
            "play.http.secret.key" -> (java.util.UUID.randomUUID().toString + java.util.UUID.randomUUID().toString),
            "play.filters.enabled" -> Seq("play.filters.csrf.CSRFFilter"),
            "play.http.filters" -> "play.api.http.EnabledFilters",
            "play.filters.csrf.cookie.name" -> "preview-csrf",
            "play.http.router" -> classOf[AllocationPreviewCsrfTestRouter].getName
          ).withFallback(Configuration(ConfigFactory.defaultReference())))
          .router(router)
          .overrides(bind[AllocationPreviewCsrfTestRouter].toInstance(router))
          .build()
        running(app) {
          val authenticatedSession = "authenticated-session=present"
          val tokenless = route(
            app,
            FakeRequest("POST", "/queue-allocation/neutral")
              .withHeaders("Content-Type" -> "text/plain", "Cookie" -> authenticatedSession)
              .withBody(write(input))
          ).get
          status(tokenless) mustBe FORBIDDEN
          calls.get() mustBe 0
          val tokenResponse = route(
            app,
            FakeRequest("GET", "/preview-csrf-token")
              .withHeaders("Accept" -> "text/html", "Cookie" -> authenticatedSession)
          ).get
          val token = contentAsString(tokenResponse)
          token must not be "missing"
          val cookie = cookies(tokenResponse).get("preview-csrf").get
          val cookieHeader = s"${cookie.name}=${cookie.value}; $authenticatedSession"
          def post(csrfToken: String) = route(
            app,
            FakeRequest("POST", "/queue-allocation/neutral")
              .withCookies(cookie)
              .withHeaders("Content-Type" -> "text/plain", "Csrf-Token" -> csrfToken, "Cookie" -> cookieHeader)
              .withBody(write(input))
          ).get
          status(post("wrong")) mustBe FORBIDDEN
          calls.get() mustBe 0
          val completed = post(token)
          status(completed) mustBe OK
          read[AllocationPreview.Response](contentAsString(completed)) mustBe response
          calls.get() mustBe 1
        }
      }
    }
  }
}

final class AllocationPreviewCsrfTestRouter(delegate: Router) extends Router {
  override def routes: PartialFunction[RequestHeader, Handler] = delegate.routes
  override def documentation: Seq[(String, String, String)] = delegate.documentation
  override def withPrefix(prefix: String): Router = new AllocationPreviewCsrfTestRouter(delegate.withPrefix(prefix))
}
