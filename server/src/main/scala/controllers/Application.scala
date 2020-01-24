package controllers

import java.nio.ByteBuffer
import java.util.{Calendar, TimeZone, UUID}

import actors._
import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.util.Timeout
import api.{KeyCloakAuth, KeyCloakAuthError, KeyCloakAuthResponse, KeyCloakAuthToken}
import boopickle.Default._
import buildinfo.BuildInfo
import com.typesafe.config.ConfigFactory
import controllers.application._
import controllers.model.ActorDataRequest
import drt.http.ProdSendAndReceive
import drt.shared.CrunchApi._
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, Arrival, _}
import drt.users.KeyCloakClient
import javax.inject.{Inject, Singleton}
import org.joda.time.chrono.ISOChronology
import org.slf4j.{Logger, LoggerFactory}
import play.api.mvc.{Action, _}
import play.api.{Configuration, Environment}
import services.PcpArrival.{pcpFrom, _}
import services.SplitsProvider.SplitProvider
import services._
import services.graphstages.Crunch
import services.graphstages.Crunch._
import services.metrics.Metrics
import services.staffing.StaffTimeSlots
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount
import test.TestDrtSystem

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {

  import scala.language.experimental.macros

  override def read[R: Pickler](p: ByteBuffer): R = Unpickle[R].fromBytes(p)

  def myroute[Trait](target: Trait): Router = macro MyMacros.routeMacro[Trait, ByteBuffer]

  override def write[R: Pickler](r: R): ByteBuffer = Pickle.intoBytes(r)
}

object PaxFlow {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def makeFlightPaxFlowCalculator(splitRatioForFlight: Arrival => Option[SplitRatios],
                                  bestPax: Arrival => Int): Arrival => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val provider = PaxLoadCalculator.flightPaxFlowProvider(splitRatioForFlight, bestPax)
    arrival => {
      val pax = bestPax(arrival)
      val paxFlow = provider(arrival)
      val summedPax = paxFlow.map(_._2.paxSum).sum
      val firstPaxTime = paxFlow.headOption.map(pf => SDate(pf._1).toString)
      log.debug(s"${Arrival.summaryString(arrival)} pax: $pax, summedFlowPax: $summedPax, deltaPax: ${pax - summedPax}, firstPaxTime: $firstPaxTime")
      paxFlow
    }
  }

  def splitRatioForFlight(splitsProviders: List[SplitProvider])
                         (flight: Arrival): Option[SplitRatios] = SplitsProvider.splitsForFlight(splitsProviders)(flight)

  def pcpArrivalTimeForFlight(timeToChoxMillis: MillisSinceEpoch, firstPaxOffMillis: MillisSinceEpoch)
                             (walkTimeProvider: FlightWalkTime)
                             (flight: Arrival): MilliDate = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeProvider)(flight)
}

trait AirportConfiguration {
  def airportConfig: AirportConfig
}

trait AirportConfProvider extends AirportConfiguration {
  val portCode: PortCode = PortCode(ConfigFactory.load().getString("portcode").toUpperCase)
  val config: Configuration

  def useStaffingInput: Boolean = config.getOptional[String]("feature-flags.use-v2-staff-input").isDefined

  def contactEmail: Option[String] = config.getOptional[String]("contact-email")

  def oohPhone: Option[String] = config.getOptional[String]("ooh-phone")

  def getPortConfFromEnvVar: AirportConfig = AirportConfigs.confByPort(portCode)

  def airportConfig: AirportConfig = getPortConfFromEnvVar.copy(
    contactEmail = contactEmail,
    outOfHoursContactPhone = oohPhone
  )
}

trait ProdPassengerSplitProviders {
  self: AirportConfiguration =>

  val csvSplitsProvider: SplitsProvider.SplitProvider = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight.flightCode, MilliDate(apiFlight.Scheduled)), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight.flightCode, MilliDate(apiFlight.Scheduled)), 0d, 0d))
}

trait ImplicitTimeoutProvider {
  implicit val timeout: Timeout = Timeout(1 second)
}

trait UserRoleProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def userRolesFromHeader(headers: Headers): Set[Role] = headers.get("X-Auth-Roles").map(_.split(",").flatMap(Roles.parse).toSet).getOrElse(Set.empty[Role])

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role]

  def getLoggedInUser(config: Configuration, headers: Headers, session: Session): LoggedInUser = {
    val baseRoles =  Set()
    val roles: Set[Role] =
      getRoles(config, headers, session) ++ baseRoles
    LoggedInUser(
      userName = headers.get("X-Auth-Username").getOrElse("Unknown"),
      id = headers.get("X-Auth-Userid").getOrElse("Unknown"),
      email = headers.get("X-Auth-Email").getOrElse("Unknown"),
      roles = roles
    )
  }
}

@Singleton
class Application @Inject()(implicit val config: Configuration,
                            implicit val mat: Materializer,
                            env: Environment,
                            val system: ActorSystem,
                            implicit val ec: ExecutionContext)
  extends InjectedController
    with AirportConfProvider
    with WithAirportConfig
    with WithAirportInfo
    with WithAlerts
    with WithAuth
    with WithContactDetails
    with WithFeatureFlags
    with WithExports
    with WithFeeds
    with WithImports
    with WithPortState
    with WithStaffing
    with WithVersion
    with ProdPassengerSplitProviders
    with ImplicitTimeoutProvider {

  val googleTrackingCode: String = config.get[String]("googleTrackingCode")

  val ctrl: DrtSystemInterface = if (isTestEnvironment) {
    new TestDrtSystem(system, config, getPortConfFromEnvVar)
  } else {
      DrtSystem(system, config, getPortConfFromEnvVar)
  }

  def isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("live") == "test"

  ctrl.run()

  val now: () => SDateLike = (() => SDate.now())

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

  log.info(s"Application using airportConfig $airportConfig")

  val cacheActorRef: AskableActorRef = system.actorOf(Props(classOf[CachingCrunchReadActor]), name = "cache-actor")

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    SDate(date.millisSinceEpoch - oneDayInMillis)
  }

  val permissionDeniedMessage = "You do not have permission manage users"

  object ApiService {
    def apply(
               airportConfig: AirportConfig,
               shiftsActor: ActorRef,
               fixedPointsActor: ActorRef,
               staffMovementsActor: ActorRef,
               headers: Headers,
               session: Session
             ): ApiService = new ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor, headers, session) {

      override implicit val timeout: Timeout = Timeout(5 seconds)

      def actorSystem: ActorSystem = system

      def getLoggedInUser(): LoggedInUser = ctrl.getLoggedInUser(config, headers, session)

      def forecastWeekSummary(startDay: MillisSinceEpoch,
                              terminal: Terminal): Future[Option[ForecastPeriodWithHeadlines]] = {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay, 7)

        val portStateFuture = portStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
        )(new Timeout(30 seconds))

        portStateFuture.map {
          case Some(portState: PortState) =>
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString()} on $terminal")
            val fp = application.Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
            val hf = application.Forecast.headlineFigures(startOfForecast, endOfForecast, terminal, portState, airportConfig.queues(terminal).toList)
            Option(ForecastPeriodWithHeadlines(fp, hf))
          case None =>
            log.info(s"No forecast available for week beginning ${SDate(startDay).toISOString()} on $terminal")
            None
        }
      }

      def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit = {
        if (getLoggedInUser().roles.contains(StaffEdit)) {
          log.info(s"Saving ${shiftsToUpdate.length} shift staff assignments")
          shiftsActor ! UpdateShifts(shiftsToUpdate)
        } else throw new Exception("You do not have permission to edit staffing.")
      }

      def getShiftsForMonth(month: MillisSinceEpoch, terminal: Terminal): Future[ShiftAssignments] = {
        val shiftsFuture = shiftsActor ? GetState

        shiftsFuture.collect {
          case shifts: ShiftAssignments =>
            log.info(s"Shifts: Retrieved shifts from actor for month starting: ${SDate(month).toISOString()}")
            val monthInLocalTime = SDate(month, Crunch.europeLondonTimeZone)
            StaffTimeSlots.getShiftsForMonth(shifts, monthInLocalTime)
        }
      }

      def keyCloakClient: KeyCloakClient with ProdSendAndReceive = {
        val token = headers.get("X-Auth-Token").getOrElse(throw new Exception("X-Auth-Token missing from headers, we need this to query the Key Cloak API."))
        val keyCloakUrl = config.getOptional[String]("key-cloak.url").getOrElse(throw new Exception("Missing key-cloak.url config value, we need this to query the Key Cloak API"))
        new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
      }

      def getKeyCloakUsers(): Future[List[KeyCloakUser]] = {
        log.info(s"Got these roles: ${getLoggedInUser().roles}")
        if (getLoggedInUser().roles.contains(ManageUsers)) {
          Future(keyCloakClient.getAllUsers().toList)
        } else throw new Exception(permissionDeniedMessage)
      }

      def getKeyCloakGroups(): Future[List[KeyCloakGroup]] = {
        if (getLoggedInUser().roles.contains(ManageUsers)) {
          keyCloakClient.getGroups
        } else throw new Exception(permissionDeniedMessage)
      }

      def getKeyCloakUserGroups(userId: UUID): Future[Set[KeyCloakGroup]] = {
        if (getLoggedInUser().roles.contains(ManageUsers)) {
          keyCloakClient.getUserGroups(userId).map(_.toSet)
        } else throw new Exception(permissionDeniedMessage)
      }

      case class KeyCloakGroups(groups: List[KeyCloakGroup])


      def addUserToGroups(userId: UUID, groups: Set[String]): Future[Unit] =
        if (getLoggedInUser().roles.contains(ManageUsers)) {
          val futureGroupIds: Future[KeyCloakGroups] = keyCloakClient
            .getGroups
            .map(kcGroups => KeyCloakGroups(kcGroups.filter(g => groups.contains(g.name))))


          futureGroupIds.map {
            case KeyCloakGroups(gps) if gps.nonEmpty =>
              log.info(s"Adding ${gps.map(_.name)} to $userId")
              gps.foreach(group => {
                val response = keyCloakClient.addUserToGroup(userId, group.id)
                response.map(res => log.info(s"Added group and got: ${res.status}  $res")
                )
              })
            case _ => log.error(s"Unable to add $userId to $groups")
          }
        } else throw new Exception(permissionDeniedMessage)

      def removeUserFromGroups(userId: UUID, groups: Set[String]): Future[Unit] =
        keyCloakClient
          .getGroups
          .map(kcGroups => kcGroups.filter(g => groups.contains(g.name))
            .foreach(g => keyCloakClient.removeUserFromGroup(userId, g.id)))

      override def portStateActor: AskableActorRef = ctrl.portStateActor

      def getShowAlertModalDialog(): Boolean = config
        .getOptional[Boolean]("feature-flags.display-modal-alert")
        .getOrElse(false)

    }
  }

  def autowireApi(path: String): Action[RawBuffer] = authByRole(BorderForceStaff) {
    Action.async(parse.raw) {
      implicit request =>
        log.debug(s"Request path: $path")

        val b = request.body.asBytes(parse.UNLIMITED).get

        val router = Router.route[Api](ApiService(airportConfig, ctrl.shiftsActor, ctrl.fixedPointsActor, ctrl.staffMovementsActor, request.headers, request.session))

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

  def index = Action { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    Ok(views.html.index("DRT - BorderForce", portCode.toString, googleTrackingCode, user.id))
  }

  def healthCheck: Action[AnyContent] = Action.async { _ =>
    val requestStart = SDate.now()
    val startMillis = getLocalLastMidnight(SDate.now()).millisSinceEpoch
    val endMillis = getLocalNextMidnight(SDate.now()).millisSinceEpoch
    val portState = ActorDataRequest.portState[PortState](ctrl.portStateActor, GetPortState(startMillis, endMillis))

    portState.map {
      case Left(liveError) =>
        log.error(s"Healthcheck failed to get live response, ${liveError.message}")
        BadGateway(
          """{
            |   "error": "Unable to retrieve live state
            |}
          """)
      case _ =>
        val requestEnd = SDate.now().millisSinceEpoch
        log.info(s"Health check request started at ${requestStart.toISOString()} and lasted ${(requestStart.millisSinceEpoch - requestEnd) / 1000} seconds ")
        NoContent
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
    import api.KeyCloakAuthTokenParserProtocol._
    import spray.json._

    def tokenToHttpResponse(username: String)(token: KeyCloakAuthResponse) = {

      token match {
        case t: KeyCloakAuthToken =>
          log.info(s"Successful login to API via keycloak for $username")
          Ok(t.toJson.toString)
        case e: KeyCloakAuthError =>
          log.info(s"Failed login to API via keycloak for $username")
          BadRequest(e.toJson.toString)
      }
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
    val token = headers.get("X-Auth-Token").getOrElse(throw new Exception("X-Auth-Token missing from headers, we need this to query the Key Cloak API."))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url").getOrElse(throw new Exception("Missing key-cloak.url config value, we need this to query the Key Cloak API"))
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
          "logTime" -> SDate(millis).toISOString(),
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
