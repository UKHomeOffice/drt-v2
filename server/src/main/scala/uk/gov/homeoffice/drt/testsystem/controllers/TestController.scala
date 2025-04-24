package uk.gov.homeoffice.drt.testsystem.controllers

import actors.persistent.staffing.ShiftsActor.ReplaceAllShifts
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import com.google.inject.Inject
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.ChromaParserProtocol._
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.server.feeds.{DqManifests, ManifestsFeedSuccess}
import drt.shared.ShiftAssignments
import drt.staff.ImportStaff
import module.NoCSRFAction
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.FlightPassengerInfoProtocol._
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import play.api.http.HeaderNames
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.testsystem.MockRoles.MockRolesProtocol._
import uk.gov.homeoffice.drt.testsystem.TestActors.ResetData
import uk.gov.homeoffice.drt.testsystem.feeds.test.CSVFixtures
import uk.gov.homeoffice.drt.testsystem.{MockRoles, TestDrtSystem}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Success

class TestController @Inject()(cc: ControllerComponents, ctrl: TestDrtSystem, noCSRFAction: NoCSRFAction) extends AbstractController(cc) {
  lazy implicit val timeout: Timeout = Timeout(5 second)

  lazy implicit val ec: ExecutionContext = ctrl.ec

  lazy implicit val system: ActorSystem = ctrl.system

  val log: Logger = LoggerFactory.getLogger(getClass)

  private def saveArrival(arrival: LiveArrival): Future[Any] = {
    log.info(s"Incoming test arrival")
    ctrl.feedService.liveFeedArrivalsActor ! FeedArrivals(List(arrival))
    ctrl.applicationService
      .setPcpTimes(Seq(arrival.toArrival(LiveFeedSource)))
      .flatMap { arrivals =>
        ctrl.actorService.portStateActor.ask(ArrivalsDiff(arrivals, List())).map { _ =>
          ctrl.feedService.liveFeedPollingActor ! AdhocCheck
        }
      }
  }

  private def saveVoyageManifest(voyageManifest: VoyageManifest): Future[Any] = {
    log.info(s"Sending Splits: ${voyageManifest.EventCode} to Test Actor")
    ctrl.applicationService
      .persistManifests(ManifestsFeedSuccess(DqManifests(0, VoyageManifests(Set(voyageManifest)).manifests)))
  }

  def resetData: Future[Any] = {
    log.info(s"Sending reset message")
    ctrl.testDrtSystemActor.restartActor.ask(ResetData)
  }

  def addArrival: Action[AnyContent] = noCSRFAction.async {
    request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[ChromaLiveFlight]) match {
        case Some(flight) =>
          val actPax: Option[Int] = Option(flight.ActPax).filter(_ != 0)
          val transPax = if (actPax.isEmpty) None else Option(flight.TranPax)
          val (carrierCode, voyageNumber, maybeSuffix) = FlightCode.flightCodeToParts(flight.IATA)
          val arrival = LiveArrival(
            operator = Option(flight.Operator),
            maxPax = Option(flight.MaxPax).filter(_ != 0),
            totalPax = actPax,
            transPax = transPax,
            terminal = Terminal(flight.Terminal),
            voyageNumber = voyageNumber.numeric,
            carrierCode = carrierCode.code,
            flightCodeSuffix = maybeSuffix.map(_.suffix),
            origin = flight.Origin,
            previousPort = None,
            scheduled = SDate(flight.SchDT).millisSinceEpoch,
            estimated = Option(SDate(flight.EstDT).millisSinceEpoch),
            touchdown = Option(SDate(flight.ActDT).millisSinceEpoch),
            estimatedChox = Option(SDate(flight.EstChoxDT).millisSinceEpoch),
            actualChox = Option(SDate(flight.ActChoxDT).millisSinceEpoch),
            status = flight.Status,
            gate = Option(flight.Gate),
            stand = Option(flight.Stand),
            runway = Option(flight.RunwayID),
            baggageReclaim = Option(flight.BaggageReclaimId),
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
            ctrl.actorService.legacyStaffAssignmentsSequentialWritesActor ! ReplaceAllShifts(shifts.assignments)
            ctrl.actorService.staffAssignmentsSequentialWritesActor ! ReplaceAllShifts(shifts.assignments)
            ctrl.shiftsService.deleteShifts()
            Created
          case _ =>
            BadRequest("{\"error\": \"Unable to parse data\"}")
        }
    }
}
