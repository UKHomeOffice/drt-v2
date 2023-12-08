package controllers

import akka.event.Logging
import api._
import buildinfo.BuildInfo
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import controllers.application._
import drt.http.ProdSendAndReceive
import org.joda.time.chrono.ISOChronology
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import services.graphstages.Crunch
import slickdb._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.auth.Roles.BorderForceStaff
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import java.sql.Timestamp
import java.util.{Calendar, TimeZone}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import uk.gov.homeoffice.drt.db.{IABFeatureDao, IUserFeedbackDao}


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
}

trait FeatureGuideProviderLike {

  val featureGuideService: FeatureGuideTableLike

  val featureGuideViewService: FeatureGuideViewLike

}

trait DropInProviderLike {

  val dropInService: DropInTableLike

  val dropInRegistrationService: DropInsRegistrationTableLike
}

trait UserFeedBackProviderLike {

  val userFeedbackService: IUserFeedbackDao
}

trait ABFeatureProviderLike {

  val abFeatureService: IABFeatureDao
}

class Application @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface)(implicit environment: Environment)
  extends AuthController(cc, ctrl) {


  val googleTrackingCode: String = config.get[String]("googleTrackingCode")

  private val systemStartGracePeriod: FiniteDuration = config.get[Int]("start-up-grace-period-seconds").seconds

  log.info(s"Scheduling crunch system to start in ${systemStartGracePeriod.toString()}")
  actorSystem.scheduler.scheduleOnce(systemStartGracePeriod) {
    ctrl.run()
  }

  val now: () => SDateLike = () => SDate.now()

  private val baseDomain: String = config.get[String]("drt.domain")

  private val isSecure: Boolean = config.get[Boolean]("drt.use-https")


  log.info(s"Starting DRTv2 build ${BuildInfo.version}")

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")

  private def defaultTimeZone: String = TimeZone.getDefault.getID

  private def systemTimeZone: String = System.getProperty("user.timezone")

  log.info(s"System.getProperty(user.timezone): $systemTimeZone")
  log.info(s"TimeZone.getDefault: $defaultTimeZone")
  assert(systemTimeZone == "UTC", "System Timezone is not set to UTC")
  assert(defaultTimeZone == "UTC", "Default Timezone is not set to UTC")

  log.info(s"timezone: ${Calendar.getInstance().getTimeZone}")

  def shouldUserViewBanner: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val feedbackExistF: Future[Boolean] = ctrl.userFeedbackService.selectByEmail(userEmail).map(_.nonEmpty)
    val bannerClosedAtF: Future[Option[Timestamp]] = ctrl.userService.selectUser(userEmail.trim).map(_.flatMap(_.feedback_banner_closed_at))
    for {
      feedbackExist <- feedbackExistF
      bannerClosedAt <- bannerClosedAtF
    } yield (feedbackExist, bannerClosedAt) match {
      case (true, _) =>
        Ok(false.toString)
      case (false, Some(closedDate)) =>
        val thirtyDays = 1000L * 60 * 60 * 24 * 30
        Ok(closedDate.before(new Timestamp(ctrl.now().millisSinceEpoch - thirtyDays)).toString)
      case (false, None) =>
        Ok(true.toString)
    }
  }

  def featureGuides: Action[AnyContent] = Action.async { _ =>
    val featureGuidesJson: Future[String] = ctrl.featureGuideService.getAll()
    featureGuidesJson.map(Ok(_))
  }

  def isNewFeatureAvailableSinceLastLogin: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val latestFeatureDateF: Future[Option[Timestamp]] = ctrl.featureGuideService.selectAll.map(_.headOption.map(_.uploadTime))
    val latestLoginDateF: Future[Option[Timestamp]] = ctrl.userService.selectUser(userEmail.trim).map(_.map(_.latest_login))
    for {
      latestFeatureDate <- latestFeatureDateF
      latestLoginDate <- latestLoginDateF
    } yield (latestFeatureDate, latestLoginDate) match {
      case (Some(featureDate), Some(loginDate)) =>
        Ok(featureDate.after(loginDate).toString)
      case _ =>
        Ok(false.toString)
    }
  }

  def recordFeatureGuideView(filename: String): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async { implicit request =>
      val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
      ctrl.featureGuideService.getGuideIdForFilename(filename).flatMap {
        case Some(id) =>
          ctrl.featureGuideViewService
            .insertOrUpdate(id, userEmail)
            .map(_ => Ok(s"File $filename viewed updated"))
        case None =>
          Future.successful(Ok(s"File $filename viewed not updated as file not found"))
      }
    }
  }

  def viewedFeatureGuideIds: Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async { implicit request =>
      import spray.json.DefaultJsonProtocol.{StringJsonFormat, immSeqFormat}
      val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
      ctrl.featureGuideViewService.featureViewed(userEmail).map(a => Ok(a.toJson.toString()))
    }
  }

  def index: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    if (user.hasRole(airportConfig.role)) {
      Ok(views.html.index("DRT - BorderForce", airportConfig.portCode.toString, googleTrackingCode, user.id))
    } else {
      val protocol = if (isSecure) "https://" else "http://"
      val fromPort = "?fromPort=" + airportConfig.portCode.toString.toLowerCase
      val redirectUrl = protocol + baseDomain + fromPort
      log.info(s"User lacks ${airportConfig.role} role. Redirecting to $redirectUrl")
      Redirect(Call("get", redirectUrl))
    }
  }

  private lazy val healthChecker: HealthChecker = if (!config.get[Boolean]("health-check.disable-feed-monitoring")) {
    val healthyResponseTimeSeconds = config.get[Int]("health-check.max-response-time-seconds")
    val defaultLastCheckThreshold = config.get[Int]("health-check.max-last-feed-check-minutes").minutes
    val feedsHealthCheckGracePeriod = config.get[Int]("health-check.feeds-grace-period-minutes").minutes
    val feedLastCheckThresholds: Map[FeedSource, FiniteDuration] = Map(
      AclFeedSource -> 7.days,
      ForecastFeedSource -> 7.days,
    )

    val feedsToMonitor = ctrl.feedActorsForPort
      .view.filterKeys(!airportConfig.feedSourceMonitorExemptions.contains(_))
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

  def apiLogin: Action[Map[String, Seq[String]]] = Action.async(parse.tolerantFormUrlEncoded) { request =>

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

    def tokenToHttpResponse(username: String)(token: KeyCloakAuthResponse): Result = token match {
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
