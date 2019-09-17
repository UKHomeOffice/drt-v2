package controllers

import java.nio.ByteBuffer
import java.util.{Calendar, TimeZone, UUID}

import actors._
import actors.pointInTime.{CrunchStateReadActor, FixedPointsReadActor}
import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, Timeout}
import api.{KeyCloakAuth, KeyCloakAuthError, KeyCloakAuthResponse, KeyCloakAuthToken}
import boopickle.CompositePickler
import boopickle.Default._
import buildinfo.BuildInfo
import com.typesafe.config.ConfigFactory
import drt.http.ProdSendAndReceive
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.Summaries.terminalSummaryForPeriod
import drt.shared.{AirportConfig, Api, Arrival, _}
import drt.staff.ImportStaff
import drt.users.{KeyCloakClient, KeyCloakGroups}
import javax.inject.{Inject, Singleton}
import org.joda.time.chrono.ISOChronology
import org.slf4j.{Logger, LoggerFactory}
import play.api.http.{HeaderNames, HttpEntity}
import play.api.libs.json._
import play.api.mvc.{Action, _}
import play.api.{Configuration, Environment}
import services.PcpArrival.{pcpFrom, _}
import services.SplitsProvider.SplitProvider
import services._
import services.graphstages.Crunch
import services.graphstages.Crunch._
import services.staffing.StaffTimeSlots
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount
import test.TestDrtSystem
import upickle.default.{read, write}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Try

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
  val portCode: String = ConfigFactory.load().getString("portcode").toUpperCase
  val config: Configuration

  def useStaffingInput: Boolean = config.getOptional[String]("feature-flags.use-v2-staff-input").isDefined

  def contactEmail: Option[String] = config.getOptional[String]("contact-email")

  def oohPhone: Option[String] = config.getOptional[String]("ooh-phone")

  def getPortConfFromEnvVar: AirportConfig = AirportConfigs.confByPort(portCode)

  def airportConfig: AirportConfig = getPortConfFromEnvVar.copy(
    useStaffingInput = useStaffingInput,
    contactEmail = contactEmail,
    outOfHoursContactPhone = oohPhone
  )
}

trait ProdPassengerSplitProviders {
  self: AirportConfiguration =>

  val csvSplitsProvider: SplitsProvider.SplitProvider = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight.IATA, MilliDate(apiFlight.Scheduled)), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight.IATA, MilliDate(apiFlight.Scheduled)), 0d, 0d))

  private implicit val timeout: Timeout = Timeout(250 milliseconds)
}

trait ImplicitTimeoutProvider {
  implicit val timeout: Timeout = Timeout(1 second)
}

trait UserRoleProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def userRolesFromHeader(headers: Headers): Set[Role] = headers.get("X-Auth-Roles").map(_.split(",").flatMap(Roles.parse).toSet).getOrElse(Set.empty[Role])

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role]

  def getLoggedInUser(config: Configuration, headers: Headers, session: Session): LoggedInUser = {
    val enableRoleBasedAccessRestrictions =
      config.getOptional[Boolean]("feature-flags.role-based-access-restrictions").getOrElse(false)
    val baseRoles = if (enableRoleBasedAccessRestrictions) Set() else Set(BorderForceStaff)
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
                            ec: ExecutionContext)
  extends InjectedController
    with AirportConfProvider
    with ApplicationWithAlerts
    with ApplicationWithImports
    with ProdPassengerSplitProviders
    with ImplicitTimeoutProvider {

  val googleTrackingCode: String = config.get[String]("googleTrackingCode")

  val ctrl: DrtSystemInterface = config.getOptional[String]("env") match {
    case Some("test") =>
      new TestDrtSystem(system, config, getPortConfFromEnvVar)
    case _ =>
      DrtSystem(system, config, getPortConfFromEnvVar)
  }
  ctrl.run()

  val virusScannerUrl: String = config.get[String]("virus-scanner-url")

  val virusScanner: VirusScanner = VirusScanner(VirusScanService(virusScannerUrl))

  def log: LoggingAdapter = system.log

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
                              terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]] = {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay, 7)

        val portStateFuture = forecastCrunchStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
        )(new Timeout(30 seconds))

        portStateFuture.map {
          case Some(portState: PortState) =>
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString()} on $terminal")
            val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
            val hf = Forecast.headlineFigures(startOfForecast, endOfForecast, terminal, portState, airportConfig.queues(terminal).toList)
            Option(ForecastPeriodWithHeadlines(fp, hf))
          case None =>
            log.info(s"No forecast available for week beginning ${SDate(startDay).toISOString()} on $terminal")
            None
        }
      }

      def forecastWeekHeadlineFigures(startDay: MillisSinceEpoch,
                                      terminal: TerminalName): Future[Option[ForecastHeadlineFigures]] = {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay, 7)

        val portStateFuture = forecastCrunchStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
        )(new Timeout(30 seconds))

        portStateFuture.map {
          case Some(portState: PortState) =>
            val hf = Forecast.headlineFigures(startOfForecast, endOfForecast, terminal, portState, airportConfig.queues(terminal).toList)
            Option(hf)
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

      def getShiftsForMonth(month: MillisSinceEpoch, terminalName: TerminalName): Future[ShiftAssignments] = {
        val shiftsFuture = shiftsActor ? GetState

        shiftsFuture.collect {
          case shifts: ShiftAssignments =>
            log.info(s"Shifts: Retrieved shifts from actor for month starting: ${SDate(month).toISOString()}")
            val monthInLocalTime = SDate(month, Crunch.europeLondonTimeZone)
            StaffTimeSlots.getShiftsForMonth(shifts, monthInLocalTime, terminalName)
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
              gps.map(group => {
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
            .map(g => keyCloakClient.removeUserFromGroup(userId, g.id)))

      override def liveCrunchStateActor: AskableActorRef = ctrl.liveCrunchStateActor

      override def forecastCrunchStateActor: AskableActorRef = ctrl.forecastCrunchStateActor

      def getShowAlertModalDialog(): Boolean = config
        .getOptional[Boolean]("feature-flags.display-modal-alert")
        .getOrElse(false)

    }
  }

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay))
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)
    val now = SDate.now()

    val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) getLocalNextMidnight(now) else startOfWeekMidnight

    (startOfForecast, endOfForecast)
  }

  def loadBestPortStateForPointInTime(day: MillisSinceEpoch, terminalName: TerminalName, startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): Future[Either[PortStateError, Option[PortState]]] =
    if (isHistoricDate(day)) {
      portStateForEndOfDay(day, terminalName)
    } else if (day <= getLocalNextMidnight(SDate.now()).millisSinceEpoch) {
      ctrl.liveCrunchStateActor.ask(GetState).map {
        case Some(ps: PortState) => Right(Option(ps))
        case _ => Right(None)
      }
    } else {
      portStateForDayInForecast(day)
    }

  def portStateForDayInForecast(day: MillisSinceEpoch): Future[Either[PortStateError, Option[PortState]]] = {
    val firstMinute = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val lastMinute = SDate(firstMinute).addHours(airportConfig.dayLengthHours).millisSinceEpoch

    val portStateFuture = ctrl.forecastCrunchStateActor.ask(GetPortState(firstMinute, lastMinute))(new Timeout(30 seconds))

    portStateFuture.map {
      case Some(ps: PortState) => Right(Option(ps))
      case _ => Right(None)
    } recover {
      case t =>
        log.warning(s"Didn't get a PortState: $t")
        Left(PortStateError(t.getMessage))
    }
  }

  def isHistoricDate(day: MillisSinceEpoch): Boolean = day < getLocalLastMidnight(SDate.now()).millisSinceEpoch

  def index = Action { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    Ok(views.html.index("DRT - BorderForce", portCode, googleTrackingCode, user.id))
  }

  def healthCheck: Action[AnyContent] = Action.async { _ =>
    val requestStart = SDate.now()
    val liveStartMillis = getLocalLastMidnight(SDate.now()).millisSinceEpoch
    val liveEndMillis = getLocalNextMidnight(SDate.now()).millisSinceEpoch
    val liveState = requestPortState[PortState](ctrl.liveCrunchStateActor, GetPortState(liveStartMillis, liveEndMillis))

    liveState.map {
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

  def getLoggedInUser(): Action[AnyContent] = Action { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)

    implicit val userWrites: Writes[LoggedInUser] = new Writes[LoggedInUser] {
      def writes(user: LoggedInUser): JsObject = Json.obj(
        "userName" -> user.userName,
        "id" -> user.id,
        "email" -> user.email,
        "roles" -> user.roles.map(_.name)
      )
    }

    Ok(Json.toJson(user))
  }

  def getAirportConfig: Action[AnyContent] = auth {
    Action { _ =>
      import upickle.default._

      Ok(write(airportConfig))
    }
  }

  def getContactDetails: Action[AnyContent] = Action { _ =>
    import upickle.default._

    Ok(write(ContactDetails(airportConfig.contactEmail, airportConfig.outOfHoursContactPhone)))
  }

  def getOOHStatus: Action[AnyContent] = Action.async { _ =>
    import upickle.default._

    val localTime = SDate.now(Crunch.europeLondonTimeZone)

    OOHChecker(BankHolidayApiClient()).isOOH(localTime).map { isOoh =>

      Ok(write(OutOfHoursStatus(localTime.toLocalDateTimeString(), isOoh)))
    }
  }

  def getAirportInfo: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { request =>
      import upickle.default._

      val res: Map[String, AirportInfo] = request.queryString.get("portCode")
        .flatMap(_.headOption)
        .map(codes => codes
          .split(",")
          .map(code => (code, AirportToCountry.airportInfo.get(code)))
          .collect {
            case (code, Some(info)) => (code, info)
          }
        ) match {
        case Some(airportInfoTuples) => airportInfoTuples.toMap
        case None => Map()
      }

      Ok(write(res))
    }
  }

  def getApplicationVersion: Action[AnyContent] = Action { _ => {
    val shouldReload = config.getOptional[Boolean]("feature-flags.version-requires-reload").getOrElse(false)
    Ok(write(BuildVersion(BuildInfo.version.toString, requiresReload = shouldReload)))
  }
  }

  def requestPortState[X](actorRef: AskableActorRef, message: Any): Future[Either[PortStateError, Option[X]]] = {
    actorRef
      .ask(message)(30 seconds)
      .map {
        case Some(ps: X) => Right(Option(ps))
        case _ => Right(None)
      }
      .recover {
        case t => Left(PortStateError(t.getMessage))
      }
  }

  def getCrunch: Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val maybeSinceMillis = request.queryString.get("since").flatMap(_.headOption.map(_.toLong))

      val maybePointInTime = if (endMillis < SDate.now().millisSinceEpoch) {
        val oneHourMillis = 1000 * 60 * 60
        Option(endMillis + oneHourMillis * 2)
      } else None

      maybeSinceMillis match {
        case None =>
          val future = futureCrunchState[PortState](maybePointInTime, startMillis, endMillis, GetPortState(startMillis, endMillis))
          future.map { updates => Ok(write(updates)) }

        case Some(sinceMillis) =>
          val future = futureCrunchState[PortStateUpdates](maybePointInTime, startMillis, endMillis, GetUpdatesSince(sinceMillis, startMillis, endMillis))
          future.map { updates => Ok(write(updates)) }
      }
    }
  }

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val message = GetPortState(startMillis, endMillis)
      val futureState = futureCrunchState[PortState](Option(pointInTime), startMillis, endMillis, message)

      futureState.map { updates => Ok(write(updates)) }
    }
  }

  def futureCrunchState[X](maybePointInTime: Option[MillisSinceEpoch], startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, request: Any): Future[Either[PortStateError, Option[X]]] = {
    maybePointInTime match {
      case Some(pit) =>
        log.info(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(Props(classOf[CrunchStateReadActor], airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queues, startMillis, endMillis))
        val futureResult = requestPortState[X](tempActor, request)
        futureResult.onSuccess {
          case _ => tempActor ! PoisonPill
        }
        futureResult
      case _ =>
        if (endMillis > getLocalNextMidnight(SDate.now().addDays(1)).millisSinceEpoch) {
          log.info(s"Regular forecast crunch state query")
          requestPortState[X](ctrl.forecastCrunchStateActor, request)
        } else {
          log.info(s"Regular live crunch state query")
          requestPortState[X](ctrl.liveCrunchStateActor, request)
        }
    }
  }

  def getFeedStatuses: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.getFeedStatus.map(s => {
        val safeStatusMessages = s.map(statusMessage => statusMessage.copy(statuses = statusMessage.statuses.map {
          case f: FeedStatusFailure =>
            f.copy(message = "Unable to connect to feed.")
          case s => s
        }))
        Ok(write(safeStatusMessages))
      })
    }
  }

  def getShouldReload: Action[AnyContent] = Action { _ =>
    val shouldRedirect: Boolean = config.getOptional[Boolean]("feature-flags.acp-redirect").getOrElse(false)
    Ok(Json.obj("reload" -> shouldRedirect))
  }

  def saveFixedPoints(): Action[AnyContent] = authByRole(FixedPointsEdit) {
    Action { request =>

      request.body.asText match {
        case Some(text) =>
          val fixedPoints: FixedPointAssignments = read[FixedPointAssignments](text)
          ctrl.fixedPointsActor ! SetFixedPoints(fixedPoints.assignments)
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  def getFixedPoints: Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>

      val fps: Future[FixedPointAssignments] = request.queryString.get("sinceMillis").flatMap(_.headOption.map(_.toLong)) match {

        case None =>
          ctrl.fixedPointsActor.ask(GetState)
            .map { case sa: FixedPointAssignments => sa }
            .recoverWith { case _ => Future(FixedPointAssignments.empty) }

        case Some(millis) =>
          val date = SDate(millis)

          val actorName = "fixed-points-read-actor-" + UUID.randomUUID().toString
          val fixedPointsReadActor: ActorRef = system.actorOf(Props(classOf[FixedPointsReadActor], date), actorName)

          fixedPointsReadActor.ask(GetState)
            .map { case sa: FixedPointAssignments =>
              fixedPointsReadActor ! PoisonPill
              sa
            }
            .recoverWith {
              case _ =>
                fixedPointsReadActor ! PoisonPill
                Future(FixedPointAssignments.empty)
            }
      }
      fps.map(fp => Ok(write(fp)))
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

  def getUserHasPortAccess(): Action[AnyContent] = auth {
    Action {
      Ok("{userHasAccess: true}")
    }
  }

  def isLoggedIn: Action[AnyContent] = Action {
    Ok("{loggedIn: true}")
  }


  def keyCloakClient(headers: Headers): KeyCloakClient with ProdSendAndReceive = {
    val token = headers.get("X-Auth-Token").getOrElse(throw new Exception("X-Auth-Token missing from headers, we need this to query the Key Cloak API."))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url").getOrElse(throw new Exception("Missing key-cloak.url config value, we need this to query the Key Cloak API"))
    new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
  }

  def exportUsers(): Action[AnyContent] = authByRole(ManageUsers) {
    Action.async { request =>
      val client = keyCloakClient(request.headers)
      client
        .getGroups
        .flatMap(groupList => KeyCloakGroups(groupList, client).usersWithGroupsCsvContent)
        .map(csvContent => Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=users-with-groups.csv")),
          HttpEntity.Strict(ByteString(csvContent), Option("application/csv"))
        ))
    }
  }

  def portStateForEndOfDay(day: MillisSinceEpoch, terminalName: TerminalName): Future[Either[PortStateError, Option[PortState]]] = {
    val relativeLastMidnight = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val startMillis = relativeLastMidnight
    val endMillis = relativeLastMidnight + oneHourMillis * airportConfig.dayLengthHours
    val pointInTime = startMillis + oneDayMillis + oneHourMillis * 3

    portStatePeriodAtPointInTime(startMillis, endMillis, pointInTime, terminalName)
  }

  def portStatePeriodAtPointInTime(startMillis: MillisSinceEpoch,
                                   endMillis: MillisSinceEpoch,
                                   pointInTime: MillisSinceEpoch,
                                   terminalName: TerminalName): Future[Either[PortStateError, Option[PortState]]] = {
    val stateQuery = GetPortStateForTerminal(startMillis, endMillis, terminalName)
    val terminalsAndQueues = airportConfig.queues.filterKeys(_ == terminalName)
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], airportConfig.portStateSnapshotInterval, SDate(pointInTime), DrtStaticParameters.expireAfterMillis, terminalsAndQueues, startMillis, endMillis), stateQuery)
    val portCrunchResult = cacheActorRef.ask(query)(new Timeout(15 seconds))


    portCrunchResult.map {
      case Some(ps: PortState) =>
        log.info(s"Got point-in-time PortState for ${SDate(pointInTime).toISOString()}")
        Right(Option(ps))
      case _ => Right(None)
    }.recover {
      case t =>
        log.warning(s"Didn't get a point-in-time PortState: $t")
        Left(PortStateError(t.getMessage))
    }
  }

  def exportDesksAndQueuesAtPointInTimeCSV(pointInTime: String,
                                           terminalName: TerminalName,
                                           startHour: Int,
                                           endHour: Int
                                          ): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action.async {

        log.info(s"Exports: For point in time ${SDate(pointInTime.toLong).toISOString()}")
        val portCode = airportConfig.portCode
        val pit = SDate(pointInTime.toLong)

        val fileName = f"$portCode-$terminalName-desks-and-queues-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
          f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

        val startMillis = dayStartMillisWithHourOffset(startHour, pit)
        val endMillis = dayStartMillisWithHourOffset(endHour, pit)
        val portStateForPointInTime = loadBestPortStateForPointInTime(pit.millisSinceEpoch, terminalName, startMillis, endMillis)
        exportDesksToCSV(terminalName, pit, startHour, endHour, portStateForPointInTime).map {
          case Some(csvData) =>
            val columnHeadings = CSVData.terminalCrunchMinutesToCsvDataHeadings(airportConfig.queues(terminalName))
            Result(
              ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
              HttpEntity.Strict(ByteString(columnHeadings + CSVData.lineEnding + csvData), Option("application/csv")))
          case None =>
            NotFound("Could not find desks and queues for this date.")
        }
      }
    }

  def dayStartMillisWithHourOffset(startHour: Int, pit: SDateLike): MillisSinceEpoch = {
    getLocalLastMidnight(pit).addHours(startHour).millisSinceEpoch
  }

  def exportDesksToCSV(terminalName: TerminalName,
                       pointInTime: SDateLike,
                       startHour: Int,
                       endHour: Int,
                       portStateFuture: Future[Either[PortStateError, Option[PortState]]]
                      ): Future[Option[String]] = {

    val startDateTime = getLocalLastMidnight(pointInTime).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pointInTime).addHours(endHour)
    val localTime = SDate(pointInTime, europeLondonTimeZone)

    portStateFuture.map {
      case Right(Some(ps: PortState)) =>
        val wps = ps.windowWithTerminalFilter(startDateTime, endDateTime, airportConfig.queues.filterKeys(_ == terminalName))
        log.debug(s"Exports: ${localTime.toISOString()} filtered to ${wps.crunchMinutes.size} CMs and ${wps.staffMinutes.size} SMs ")
        Option(CSVData.terminalMinutesToCsvData(wps.crunchMinutes, wps.staffMinutes, airportConfig.queues(terminalName), startDateTime, endDateTime, 15))

      case unexpected =>
        log.error(s"Exports: Got the wrong thing $unexpected for Point In time: ${localTime.toISOString()}")

        None
    }
  }

  def exportForecastWeekToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = authByRole(ForecastView) {
    Action.async {
      val (startOfForecast, endOfForecast) = startAndEndForDay(startDay.toLong, 180)

      val portStateFuture = ctrl.forecastCrunchStateActor.ask(
        GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
      )(new Timeout(30 seconds))

      val portCode = airportConfig.portCode

      val fileName = f"$portCode-$terminal-forecast-export-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
      portStateFuture.map {
        case Some(portState: PortState) =>
          val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
          val csvData = CSVData.forecastPeriodToCsv(fp)
          Result(
            ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
            HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
          )

        case None =>
          log.error(s"Forecast CSV Export: Missing planning data for ${startOfForecast.ddMMyyString} for Terminal $terminal")
          NotFound(s"Sorry, no planning summary available for week starting ${startOfForecast.ddMMyyString}")
      }
    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = authByRole(ForecastView) {
    Action.async {
      val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay.toLong))
      val endOfForecast = startOfWeekMidnight.addDays(180)
      val now = SDate.now()

      val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
        log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
        getLocalNextMidnight(now)
      } else startOfWeekMidnight

      val portStateFuture = ctrl.forecastCrunchStateActor.ask(
        GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
      )(new Timeout(30 seconds))

      val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
      portStateFuture.map {
        case Some(portState: PortState) =>
          val hf: ForecastHeadlineFigures = Forecast.headlineFigures(startOfForecast, endOfForecast, terminal, portState, airportConfig.queues(terminal).toList)
          val csvData = CSVData.forecastHeadlineToCSV(hf, airportConfig.exportQueueOrder)
          Result(
            ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
            HttpEntity.Strict(ByteString(csvData), Option("application/csv")
            )
          )

        case None =>
          log.error(s"Missing headline data for ${startOfWeekMidnight.ddMMyyString} for Terminal $terminal")
          NotFound(s"Sorry, no headlines available for week starting ${startOfWeekMidnight.ddMMyyString}")
      }
    }
  }

  def exportApi(day: Int, month: Int, year: Int, terminalName: TerminalName): Action[AnyContent] = authByRole(ApiViewPortCsv) {
    Action.async { _ =>
      val startHour = 0
      val endHour = 24
      val dateOption = Try(SDate(year, month, day, 0, 0)).toOption
      val terminalNameOption = airportConfig.terminalNames.find(name => name == terminalName)
      val resultOption = for {
        date <- dateOption
        terminalName <- terminalNameOption
      } yield {
        val pit = date.millisSinceEpoch
        val startMillis = dayStartMillisWithHourOffset(startHour, date)
        val endMillis = dayStartMillisWithHourOffset(endHour, date)
        val portStateForPointInTime = loadBestPortStateForPointInTime(pit, terminalName, startMillis, endMillis)
        val fileName = f"export-splits-$portCode-$terminalName-${date.getFullYear()}-${date.getMonth()}-${date.getDate()}"
        flightsForCSVExportWithinRange(terminalName, date, startHour = startHour, endHour = endHour, portStateForPointInTime).map {
          case Some(csvFlights) =>
            val csvData = CSVData.flightsWithSplitsWithAPIActualsToCSVWithHeadings(csvFlights)
            Result(
              ResponseHeader(OK, Map(
                CONTENT_LENGTH -> csvData.length.toString,
                CONTENT_TYPE -> "text/csv",
                CONTENT_DISPOSITION -> s"attachment; filename=$fileName.csv",
                CACHE_CONTROL -> "no-cache")
              ),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
            )
          case None => NotFound("No data for this date")
        }
      }
      resultOption.getOrElse(
        Future(BadRequest("Invalid terminal name or date"))
      )
    }
  }

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String,
                                              terminalName: TerminalName,
                                              startHour: Int,
                                              endHour: Int): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      implicit request =>
        val pit = SDate(pointInTime.toLong)

        val portCode = airportConfig.portCode
        val fileName = f"$portCode-$terminalName-arrivals-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
          f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

        val startMillis = dayStartMillisWithHourOffset(startHour, pit)
        val endMillis = dayStartMillisWithHourOffset(endHour, pit)
        val portStateForPointInTime = loadBestPortStateForPointInTime(pit.millisSinceEpoch, terminalName, startMillis, endMillis)
        flightsForCSVExportWithinRange(terminalName, pit, startHour, endHour, portStateForPointInTime).map {
          case Some(csvFlights) =>
            val csvData = if (ctrl.getRoles(config, request.headers, request.session).contains(ApiView)) {
              log.info(s"Sending Flights CSV with API data")
              CSVData.flightsWithSplitsWithAPIActualsToCSVWithHeadings(csvFlights)
            }
            else {
              log.info(s"Sending Flights CSV with no API data")
              CSVData.flightsWithSplitsToCSVWithHeadings(csvFlights)
            }
            Result(
              ResponseHeader(200, Map(
                "Content-Disposition" -> s"attachment; filename=$fileName.csv",
                HeaderNames.CACHE_CONTROL -> "no-cache")
              ),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
            )
          case None => NotFound("No data for this date")
        }
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(start: String,
                                                  end: String,
                                                  terminalName: TerminalName): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
      val endPit = SDate(end.toLong, europeLondonTimeZone)

      val portCode = airportConfig.portCode
      val fileName = makeFileName("arrivals", terminalName, startPit, endPit, portCode)

      val dayRangeInMillis = startPit.millisSinceEpoch to endPit.millisSinceEpoch by oneDayMillis
      val startHour = 0
      val endHour = 24
      val days: Seq[Future[Option[String]]] = dayRangeInMillis.zipWithIndex.map {

        case (dayMillis, index) =>
          val day = SDate(dayMillis)
          val startMillis = dayStartMillisWithHourOffset(startHour, day)
          val endMillis = dayStartMillisWithHourOffset(endHour, day)
          val csvFunc = if (index == 0) CSVData.flightsWithSplitsToCSVWithHeadings _ else CSVData.flightsWithSplitsToCSV _
          flightsForCSVExportWithinRange(
            terminalName = terminalName,
            pit = day,
            startHour = startHour,
            endHour = endHour,
            portStateFuture = loadBestPortStateForPointInTime(dayMillis, terminalName, startMillis, endMillis)
          ).map {
            case Some(fs) => Option(csvFunc(fs))
            case None =>
              log.error(s"Missing a day of flights")
              None
          }
      }

      CSVData.multiDayToSingleExport(days).map(csvData => {
        Result(ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv")))
      })
    }
  }

  def exportDesksAndQueuesBetweenTimeStampsCSV(start: String,
                                               end: String,
                                               terminalName: TerminalName): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async {
      val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
      val endPit = SDate(end.toLong, europeLondonTimeZone)

      val portCode = airportConfig.portCode
      val fileName = makeFileName("desks-and-queues", terminalName, startPit, endPit, portCode)

      val dayRangeMillis = startPit.millisSinceEpoch to endPit.millisSinceEpoch by oneDayMillis
      val daysMillisSource: Future[Seq[Option[String]]] = Source(dayRangeMillis)
        .mapAsync(config.get[Int]("multi-day-parallelism")) { millis =>
          val startHour = 0
          val endHour = 24
          val day = SDate(millis)
          val startMillis = dayStartMillisWithHourOffset(startHour, day)
          val endMillis = dayStartMillisWithHourOffset(endHour, day)
          exportDesksToCSV(
            terminalName = terminalName,
            pointInTime = day,
            startHour = startHour,
            endHour = endHour,
            portStateFuture = loadBestPortStateForPointInTime(millis, terminalName, startMillis, endMillis)
          )
        }.runWith(Sink.seq)

      CSVData.multiDayToSingleExport(daysMillisSource).map(csvData => {
        Result(ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
          HttpEntity.Strict(ByteString(
            CSVData.terminalCrunchMinutesToCsvDataHeadings(airportConfig.queues(terminalName)) + CSVData.lineEnding + csvData
          ), Option("application/csv")))
      })
    }
  }

  def makeFileName(subject: String,
                   terminalName: TerminalName,
                   startPit: SDateLike,
                   endPit: SDateLike,
                   portCode: String): String = {
    f"$portCode-$terminalName-$subject-" +
      f"${startPit.getFullYear()}-${startPit.getMonth()}%02d-${startPit.getDate()}-to-" +
      f"${endPit.getFullYear()}-${endPit.getMonth()}%02d-${endPit.getDate()}"
  }


  def flightsForCSVExportWithinRange(terminalName: TerminalName,
                                     pit: SDateLike,
                                     startHour: Int,
                                     endHour: Int,
                                     portStateFuture: Future[Either[PortStateError, Option[PortState]]]
                                    ): Future[Option[List[ApiFlightWithSplits]]] = {

    val startDateTime = getLocalLastMidnight(pit).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pit).addHours(endHour)
    val isInRange = isInRangeOnDay(startDateTime, endDateTime) _

    portStateFuture.map {
      case Right(Some(PortState(fs, _, _))) =>

        val flightsForTerminalInRange = fs.values
          .filter(_.apiFlight.Terminal == terminalName)
          .filter(_.apiFlight.PcpTime.isDefined)
          .filter(f => isInRange(SDate(f.apiFlight.PcpTime.getOrElse(0L), europeLondonTimeZone)))
          .toList

        Option(flightsForTerminalInRange)
      case unexpected =>
        log.error(s"got the wrong thing extracting flights from PortState (terminal: $terminalName, millis: $pit," +
          s" start hour: $startHour, endHour: $endHour): Error: $unexpected")
        None
    }
  }

  def saveStaff(): Action[AnyContent] = authByRole(StaffEdit) {
    Action {
      implicit request =>
        val maybeShifts: Option[ShiftAssignments] = request.body.asJson.flatMap(ImportStaff.staffJsonToShifts)

        maybeShifts match {
          case Some(shifts) =>
            log.info(s"Received ${shifts.assignments.length} shifts. Sending to actor")
            ctrl.shiftsActor ! SetShifts(shifts.assignments)
            Created
          case _ =>
            BadRequest("{\"error\": \"Unable to parse data\"}")
        }
    }
  }

  def authByRole[A](allowedRole: Role)(action: Action[A]): Action[A] = Action.async(action.parser) { request =>
    val loggedInUser: LoggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    log.debug(s"${loggedInUser.roles}, allowed role $allowedRole")
    val enableRoleBasedAccessRestrictions =
      config.getOptional[Boolean]("feature-flags.role-based-access-restrictions").getOrElse(false)
    val preventAccess = !loggedInUser.hasRole(allowedRole) && enableRoleBasedAccessRestrictions

    if (!preventAccess) {
      auth(action)(request)
    } else {
      log.error("Unauthorized")
      Future(Unauthorized(
        s"""
           |{
           |  message: "Permission denied, you need $allowedRole to access this resource"
           |}
         """.stripMargin))
    }
  }

  def auth[A](action: Action[A]): Action[A] = Action.async(action.parser) { request =>

    val loggedInUser: LoggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    val allowedRole = airportConfig.role

    val enablePortAccessRestrictions =
      config.getOptional[Boolean]("feature-flags.port-access-restrictions").getOrElse(false)

    if (!loggedInUser.hasRole(allowedRole))
      log.warning(
        s"User missing port role: ${loggedInUser.email} is accessing ${airportConfig.portCode} " +
          s"and has ${loggedInUser.roles.mkString(", ")} (needs $allowedRole)"
      )

    val preventAccess = !loggedInUser.hasRole(allowedRole) && enablePortAccessRestrictions

    if (preventAccess) {
      Future(Unauthorized(
        s"""
           |{
           |  message: "Permission denied, you need $allowedRole to access this resource"
           |}
         """.stripMargin))
    } else {
      action(request)
    }
  }

  def autowireApi(path: String): Action[RawBuffer] = authByRole(BorderForceStaff) {
    Action.async(parse.raw) {
      implicit request =>
        log.debug(s"Request path: $path")

        val b = request.body.asBytes(parse.UNLIMITED).get

        // call Autowire route

        implicit val staffAssignmentsPickler: CompositePickler[StaffAssignments] = compositePickler[StaffAssignments].addConcreteType[ShiftAssignments].addConcreteType[FixedPointAssignments]
        implicit val apiPaxTypeAndQueueCountPickler: Pickler[ApiPaxTypeAndQueueCount] = generatePickler[ApiPaxTypeAndQueueCount]
        implicit val feedStatusPickler: CompositePickler[FeedStatus] = compositePickler[FeedStatus].
          addConcreteType[FeedStatusSuccess].
          addConcreteType[FeedStatusFailure]

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

object Forecast {
  def headlineFigures(startOfForecast: SDateLike, endOfForecast: SDateLike, terminal: TerminalName, portState: PortState, queues: List[QueueName]): ForecastHeadlineFigures = {
    val dayMillis = 60 * 60 * 24 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / dayMillis
    val crunchSummaryDaily = portState.crunchSummary(startOfForecast, periods, 1440, terminal, queues)

    val figures = for {
      (dayMillis, queueMinutes) <- crunchSummaryDaily
      (queue, queueMinute) <- queueMinutes
    } yield {
      QueueHeadline(dayMillis, queue, queueMinute.paxLoad.toInt, queueMinute.workLoad.toInt)
    }
    ForecastHeadlineFigures(figures.toSeq)
  }

  def forecastPeriod(airportConfig: AirportConfig, terminal: TerminalName, startOfForecast: SDateLike, endOfForecast: SDateLike, portState: PortState): ForecastPeriod = {
    val fifteenMinuteMillis = 15 * 60 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / fifteenMinuteMillis
    val staffSummary = portState.staffSummary(startOfForecast, periods, 15, terminal)
    val crunchSummary15Mins = portState.crunchSummary(startOfForecast, periods, 15, terminal, airportConfig.nonTransferQueues(terminal).toList)
    val timeSlotsByDay = Forecast.rollUpForWeek(crunchSummary15Mins, staffSummary)
    ForecastPeriod(timeSlotsByDay)
  }

  def rollUpForWeek(crunchSummary: Map[MillisSinceEpoch, Map[QueueName, CrunchMinute]],
                    staffSummary: Map[MillisSinceEpoch, StaffMinute]
                   ): Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] =
    crunchSummary
      .map { case (millis, cms) =>
        val (available, fixedPoints) = staffSummary.get(millis).map(sm => (sm.shifts, sm.fixedPoints)).getOrElse((0, 0))
        val deskStaff = if (cms.nonEmpty) cms.values.map(_.deskRec).sum else 0
        ForecastTimeSlot(millis, available, fixedPoints + deskStaff)
      }
      .groupBy(forecastTimeSlot => getLocalLastMidnight(SDate(forecastTimeSlot.startMillis)).millisSinceEpoch)
      .mapValues(_.toSeq)
}

case class GetTerminalCrunch(terminalName: TerminalName)
