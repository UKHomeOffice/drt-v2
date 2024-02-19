package uk.gov.homeoffice.drt.testsystem.controllers

import actors.{TestDrtSystemActors, TestDrtSystemActorsLike}
import actors.persistent.staffing.ReplaceAllShifts
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.ChromaParserProtocol._
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.server.feeds.Implicits._
import drt.shared.ShiftAssignments
import drt.staff.ImportStaff
import module.NoCSRFAction
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.FlightPassengerInfoProtocol._
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import play.api.http.HeaderNames
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.testsystem.TestActors.ResetData
import uk.gov.homeoffice.drt.testsystem.MockRoles.MockRolesProtocol._
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.testsystem.MockRoles
import uk.gov.homeoffice.drt.testsystem.feeds.test.CSVFixtures
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Success

class TestController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface, noCSRFAction: NoCSRFAction) extends AbstractController(cc) {
  lazy implicit val timeout: Timeout = Timeout(5 second)

  lazy implicit val ec: ExecutionContext = ctrl.ec

  lazy implicit val system = ctrl.system

  val log: Logger = LoggerFactory.getLogger(getClass)

  val testDrtSystemActor: TestDrtSystemActorsLike =  TestDrtSystemActors(ctrl.applicationService,
    ctrl.feedService,
    ctrl.actorService,
    ctrl.persistentActors,
    ctrl.config)

  def saveArrival(arrival: Arrival): Future[Any] = {
    log.info(s"Incoming test arrival")
    testDrtSystemActor.testArrivalActor.ask(arrival).map { _ =>
      ctrl.feedService.liveActor ! AdhocCheck
    }
  }

  def saveVoyageManifest(voyageManifest: VoyageManifest): Future[Any] = {
    log.info(s"Sending Splits: ${voyageManifest.EventCode} to Test Actor")
    testDrtSystemActor.testManifestsActor.ask(VoyageManifests(Set(voyageManifest)))
  }

  def resetData: Future[Any] = {
    log.info(s"Sending reset message")
    testDrtSystemActor.restartActor.ask(ResetData)
  }

  def hello = Action {
    Ok("Hello")
  }

  def addArrival: Action[AnyContent] = noCSRFAction.async {
    request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[ChromaLiveFlight]) match {
        case Some(flight) =>
          val walkTimeMinutes = 4
          val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
          val actPax = Option(flight.ActPax).filter(_ != 0)
          val arrival = Arrival(
            Operator = flight.Operator,
            Status = flight.Status,
            Estimated = Option(SDate(flight.EstDT).millisSinceEpoch),
            Actual = Option(SDate(flight.ActDT).millisSinceEpoch),
            EstimatedChox = Option(SDate(flight.EstChoxDT).millisSinceEpoch),
            Predictions = Predictions(0L, Map()),
            ActualChox = Option(SDate(flight.ActChoxDT).millisSinceEpoch),
            Gate = Option(flight.Gate),
            Stand = Option(flight.Stand),
            MaxPax = Option(flight.MaxPax).filter(_ != 0),
            RunwayID = Option(flight.RunwayID),
            BaggageReclaimId = Option(flight.BaggageReclaimId),
            AirportID = PortCode(flight.AirportID),
            Terminal = Terminal(flight.Terminal),
            rawICAO = flight.ICAO,
            rawIATA = flight.IATA,
            Origin = PortCode(flight.Origin),
            PcpTime = Option(pcpTime),
            FeedSources = Set(LiveFeedSource),
            PassengerSources = Map(LiveFeedSource -> Passengers(actPax, if (actPax.isEmpty) None else Option(flight.TranPax))),
            Scheduled = SDate(flight.SchDT).millisSinceEpoch
          )
          saveArrival(arrival).map(_ => Created)
        case None =>
          Future(BadRequest(s"Unable to parse JSON: ${request.body.asText}"))
      }
  }

  def addArrivals(forDate: String): Action[AnyContent] = noCSRFAction.async {
    _.body.asMultipartFormData.flatMap(_.files.find(_.key == "data")) match {
      case Some(f) =>
        val path = f.ref.path.toString

        val saveFutures = CSVFixtures
          .csvPathToArrivalsOnDate(forDate, path)
          .collect {
            case Success(a) => saveArrival(a)
          }

        Future.sequence(saveFutures).map(_ => Created.withHeaders(HeaderNames.ACCEPT -> "application/csv"))

      case None =>
        Future(BadRequest("You must post a CSV file with name \"data\""))
    }
  }

  def addManifest: Action[AnyContent] = noCSRFAction.async {
    request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[VoyageManifest]) match {
        case Some(vm) =>
          log.info(s"Got a manifest to save ${vm.CarrierCode}${vm.VoyageNumber} ${vm.ScheduledDateOfArrival} ${vm.ScheduledTimeOfArrival}")
          saveVoyageManifest(vm).map(_ => Created)
        case None =>
          Future(BadRequest(s"Unable to parse JSON: ${request.body.asText}"))
      }
  }

  def setMockRoles: Action[AnyContent] = noCSRFAction.async {
    implicit request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[MockRoles]) match {
        case Some(roles) =>
          log.info(s"Got mock roles to set: $roles")

          log.info(s"Replacing these mock roles: ${request.session.data}")
          log.info(s"mock headers: ${request.headers}")

          Future.successful(Created.withSession(Session(Map("mock-roles" -> roles.roles.map(_.name).mkString(",")))))
        case None =>
          Future.successful(BadRequest(s"Unable to parse JSON: ${request.body.asText}"))
      }
  }

  def setMockRolesByQueryString: Action[AnyContent] = noCSRFAction.async {
    implicit request =>
      request.queryString.get("roles") match {
        case Some(rs) =>
          Future.successful(Redirect("/").withSession(Session(Map("mock-roles" -> rs.mkString(",")))))
        case roles =>
          Future.successful(BadRequest(s"""Unable to parse roles: $roles from query string ${request.queryString}"""))
      }
  }

  def deleteAllData: Action[AnyContent] = noCSRFAction.async { _ =>
    resetData.map(_ => Accepted)
  }

  def replaceAllShifts: Action[AnyContent] =
    Action {
      implicit request =>
        val maybeShifts: Option[ShiftAssignments] = request.body.asJson.flatMap(ImportStaff.staffJsonToShifts)

        maybeShifts match {
          case Some(shifts) =>
            log.info(s"Received ${shifts.assignments.length} shifts. Sending to actor")
            ctrl.applicationService.shiftsSequentialWritesActor ! ReplaceAllShifts(shifts.assignments)
            Created
          case _ =>
            BadRequest("{\"error\": \"Unable to parse data\"}")
        }
    }
}
