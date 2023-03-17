package controllers

import actors._
import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.stream._
import akka.util.Timeout
import api._
import boopickle.Default._
import buildinfo.BuildInfo
import com.typesafe.config.ConfigFactory
import controllers.application._
import drt.http.ProdSendAndReceive
import drt.shared._
import drt.users.KeyCloakClient
import org.joda.time.chrono.ISOChronology
import org.slf4j.{Logger, LoggerFactory}
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import services.graphstages.Crunch
import services.metrics.Metrics
import slickdb.UserTableLike
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, Role}
import uk.gov.homeoffice.drt.auth._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AclFeedSource, AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import java.nio.ByteBuffer
import java.util.{Calendar, TimeZone}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {

  import scala.language.experimental.macros

  override def read[R: Pickler](p: ByteBuffer): R = Unpickle[R].fromBytes(p)

  def myroute[Trait](target: Trait): Router = macro MyMacros.routeMacro[Trait, ByteBuffer]

  override def write[R: Pickler](r: R): ByteBuffer = Pickle.intoBytes(r)
}

trait AirportConfiguration {
  def airportConfig: AirportConfig
}

trait AirportConfProvider extends AirportConfiguration {
  val portCode: PortCode = PortCode(ConfigFactory.load().getString("portcode").toUpperCase)
  val config: Configuration

  def contactEmail: Option[String] = config.getOptional[String]("contact-email")

  def oohPhone: Option[String] = config.getOptional[String]("ooh-phone")

  def useTimePredictions: Boolean = config.get[Boolean]("feature-flags.use-time-predictions")

  def noLivePortFeed: Boolean = config.get[Boolean]("feature-flags.no-live-port-feed")

  def aclDisabled: Boolean = config.getOptional[Boolean]("acl.disabled").getOrElse(false)

  def idealStaffAsDefault: Boolean = config.getOptional[Boolean]("feature-flags.use-ideal-staff-default").getOrElse(false)

  private def getPortConfFromEnvVar: AirportConfig = DrtPortConfigs.confByPort(portCode)

  lazy val airportConfig: AirportConfig = {
    val configForPort = getPortConfFromEnvVar.copy(
      contactEmail = contactEmail,
      outOfHoursContactPhone = oohPhone,
      useTimePredictions = useTimePredictions,
      noLivePortFeed = noLivePortFeed,
      aclDisabled = aclDisabled,
      idealStaffAsDefault = idealStaffAsDefault
    )

    configForPort.assertValid()

    configForPort
  }
}

trait UserRoleProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val userService: UserTableLike

  def userRolesFromHeader(headers: Headers): Set[Role] = headers.get("X-Auth-Roles").map(_.split(",").flatMap(Roles.parse).toSet).getOrElse(Set.empty[Role])

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role]

  def getLoggedInUser(config: Configuration, headers: Headers, session: Session)(implicit ec: ExecutionContext): LoggedInUser = {
    val baseRoles = Set()
    val roles: Set[Role] =
      getRoles(config, headers, session) ++ baseRoles
    val loggedInUser: LoggedInUser = LoggedInUser(
      userName = headers.get("X-Auth-Username").getOrElse("Unknown"),
      id = headers.get("X-Auth-Userid").getOrElse("Unknown"),
      email = headers.get("X-Auth-Email").getOrElse("Unknown"),
      roles = roles
    )
    userService.insertOrUpdateUser(loggedInUser, None, None)

    loggedInUser
  }
}

@Singleton
class Application @Inject()(implicit val config: Configuration, env: Environment)
  extends InjectedController
    with AirportConfProvider
    with WithAirportConfig
    with WithAirportInfo
    with WithRedLists
    with WithEgateBanks
    with WithAlerts
    with WithAuth
    with WithContactDetails
    with WithFeatureFlags
    with WithExports
    with WithFeeds
    with WithImports
    with WithPortState
    with WithStaffing
    with WithApplicationInfo
    with WithSimulations
    with WithPassengerInfo
    with WithWalkTimes
    with WithDebug
    with WithEmailFeedback
    with WithForecastAccuracy {

  implicit val system: ActorSystem = DrtActorSystem.actorSystem
  implicit val mat: Materializer = DrtActorSystem.mat
  implicit val ec: ExecutionContext = DrtActorSystem.ec

  implicit val timeout: Timeout = new Timeout(30 seconds)

  val googleTrackingCode: String = config.get[String]("googleTrackingCode")

  val ctrl: DrtSystemInterface = DrtActorSystem.drtSystem

  ctrl.run()

  val now: () => SDateLike = () => SDate.now()

  lazy val govNotifyApiKey = config.get[String]("notifications.gov-notify-api-key")

  lazy val negativeFeedbackTemplateId = config.get[String]("notifications.negative-feedback-templateId")

  lazy val positiveFeedbackTemplateId = config.get[String]("notifications.positive-feedback-templateId")

  lazy val govNotifyReference = config.get[String]("notifications.reference")

  val virusScannerUrl: String = config.get[String]("virus-scanner-url")

  val virusScanner: VirusScanner = VirusScanner(VirusScanService(virusScannerUrl))

  val log: LoggingAdapter = system.log

  log.info(s"Starting DRTv2 build ${BuildInfo.version}")

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")

  def defaultTimeZone: String = TimeZone.getDefault.getID

  def systemTimeZone: String = System.getProperty("user.timezone")

  log.info(s"System.getProperty(user.timezone): $systemTimeZone")
  log.info(s"TimeZone.getDefault: $defaultTimeZone")
  assert(systemTimeZone == "UTC", "System Timezone is not set to UTC")
  assert(defaultTimeZone == "UTC", "Default Timezone is not set to UTC")

  log.info(s"timezone: ${Calendar.getInstance().getTimeZone}")

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    SDate(date.millisSinceEpoch - oneDayInMillis)
  }


  def autowireApi(path: String): Action[RawBuffer] = authByRole(BorderForceStaff) {
    Action.async(parse.raw) {
      implicit request =>
        log.debug(s"Request path: $path")

        val b = request.body.asBytes(parse.UNLIMITED).get

        val router = Router.route[Api](
          new ApiService(airportConfig,
            ctrl.shiftsActor,
            request.headers,
            request.session))

        router(
          autowire.Core.Request(path.split("/"), Unpickle[Map[String, ByteBuffer]].fromBytes(b.asByteBuffer))
        ).map(buffer => {
          val data = Array.ofDim[Byte](buffer.remaining())
          buffer.get(data)
          Ok(data)
        })
    }
  }

  def timedEndPoint[A](name: String, maybeParams: Option[String] = None)(eventualThing: Future[A]): Future[A] = {
    val startMillis = SDate.now().millisSinceEpoch
    eventualThing.foreach { _ =>
      val endMillis = SDate.now().millisSinceEpoch
      val millisTaken = endMillis - startMillis
      Metrics.timer(s"$name", millisTaken)
      log.info(s"$name${maybeParams.map(p => s" - $p").getOrElse("")} took ${millisTaken}ms")
    }
    eventualThing
  }

  def index: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    if (user.hasRole(airportConfig.role)) {
      Ok(views.html.index("DRT - BorderForce", portCode.toString, googleTrackingCode, user.id))
    } else {
      val baseDomain = config.get[String]("drt.domain")
      val isSecure = config.get[Boolean]("drt.use-https")
      val protocol = if (isSecure) "https://" else "http://"
      val fromPort = "?fromPort=" + airportConfig.portCode.toString.toLowerCase
      val redirectUrl = protocol + baseDomain + fromPort
      log.info(s"User lacks ${airportConfig.role} role. Redirecting to $redirectUrl")
      Redirect(Call("get", redirectUrl))
    }
  }

  lazy val healthChecker: HealthChecker = if (!config.get[Boolean]("health-check.disable-feed-monitoring")) {
    val healthyResponseTimeSeconds = config.get[Int]("health-check.max-response-time-seconds")
    val defaultLastCheckThreshold = config.get[Int]("health-check.max-last-feed-check-minutes").minutes
    val feedsHealthCheckGracePeriod = config.get[Int]("health-check.feeds-grace-period-minutes").minutes
    val feedLastCheckThresholds: Map[FeedSource, FiniteDuration] = Map(
      AclFeedSource -> 26.hours
    )

    val feedsToMonitor = ctrl.feedActorsForPort
      .filterKeys(!airportConfig.feedSourceMonitorExemptions.contains(_))
      .values.toList

    HealthChecker(Seq(
      FeedsHealthCheck(feedsToMonitor, defaultLastCheckThreshold, feedLastCheckThresholds, now, feedsHealthCheckGracePeriod),
      ActorResponseTimeHealthCheck(ctrl.portStateActor, healthyResponseTimeSeconds * MilliTimes.oneSecondMillis))
    )
  } else {
    HealthChecker(Seq())
  }

  def healthCheck: Action[AnyContent] = Action.async { _ =>
    healthChecker.checksPassing.map {
      case true => Ok("health check ok")
      case _ => InternalServerError("health check failed")
    }
  }

  def apiLogin(): Action[Map[String, Seq[String]]] = Action.async(parse.tolerantFormUrlEncoded) { request =>

    def postStringValOrElse(key: String): Option[String] = {
      request.body.get(key).map(_.head)
    }

    val tokenUrlOption = config.getOptional[String]("key-cloak.token_url")
    val clientIdOption = config.getOptional[String]("key-cloak.client_id")
    val clientSecretOption = config.getOptional[String]("key-cloak.client_secret")
    val usernameOption = postStringValOrElse("username")
    val passwordOption = postStringValOrElse("password")
    import KeyCloakAuthTokenParserProtocol._
    import spray.json._

    def tokenToHttpResponse(username: String)(token: KeyCloakAuthResponse) = token match {
      case t: KeyCloakAuthToken =>
        log.info(s"Successful login to API via keycloak for $username")
        Ok(t.toJson.toString)
      case e: KeyCloakAuthError =>
        log.info(s"Failed login to API via keycloak for $username")
        BadRequest(e.toJson.toString)
    }

    def missingPostFieldsResponse = Future(
      BadRequest(KeyCloakAuthError("invalid_form_data", "You must provide a username and password").toJson.toString)
    )

    val result: Option[Future[Result]] = for {
      tokenUrl <- tokenUrlOption
      clientId <- clientIdOption
      clientSecret <- clientSecretOption
    } yield (usernameOption, passwordOption) match {
      case (Some(username), Some(password)) =>
        val authClient = new KeyCloakAuth(tokenUrl, clientId, clientSecret) with ProdSendAndReceive
        authClient.getToken(username, password).map(tokenToHttpResponse(username))
      case _ =>
        log.info(s"Invalid post fields for api login.")
        missingPostFieldsResponse
    }

    def disabledFeatureResponse = Future(NotImplemented(
      KeyCloakAuthError(
        "feature_not_implemented",
        "This feature is not currently available for this port on DRT"
      ).toJson.toString
    ))

    result match {
      case Some(f) => f.map(t => t)
      case None =>
        disabledFeatureResponse
    }
  }

  def keyCloakClient(headers: Headers): KeyCloakClient with ProdSendAndReceive = {
    val token = headers.get("X-Auth-Token")
      .getOrElse(throw new Exception("X-Auth-Token missing from headers, we need this to query the Key Cloak API."))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url")
      .getOrElse(throw new Exception("Missing key-cloak.url config value, we need this to query the Key Cloak API"))
    new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
  }

  def logging: Action[Map[String, Seq[String]]] = auth {
    Action(parse.tolerantFormUrlEncoded) {
      implicit request =>

        def postStringValOrElse(key: String, default: String) = {
          request.body.get(key).map(_.head).getOrElse(default)
        }

        val logLevel = postStringValOrElse("level", "ERROR")

        val millis = request.body.get("timestamp")
          .map(_.head.toLong)
          .getOrElse(SDate.now(Crunch.europeLondonTimeZone).millisSinceEpoch)

        val logMessage = Map(
          "logger" -> ("CLIENT - " + postStringValOrElse("logger", "log")),
          "message" -> postStringValOrElse("message", "no log message"),
          "logTime" -> SDate(millis).toISOString,
          "url" -> postStringValOrElse("url", request.headers.get("referrer").getOrElse("unknown url")),
          "logLevel" -> logLevel
        )

        log.log(Logging.levelFor(logLevel).getOrElse(Logging.ErrorLevel), s"Client Error: ${
          logMessage.map {
            case (value, key) => s"$key: $value"
          }.mkString(", ")
        }")

        Ok("logged successfully")
    }
  }
}

case class GetTerminalCrunch(terminalName: Terminal)
