package test.controllers

import akka.pattern.ask
import akka.util.Timeout
import controllers.{AirportConfProvider, DrtActorSystem}
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.ChromaParserProtocol._
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.server.feeds.Implicits._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.FlightPassengerInfoProtocol._
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.mvc.{Action, AnyContent, InjectedController, Session}
import uk.gov.homeoffice.drt.time.SDate
import spray.json._
import test.TestActors.ResetData
import test.TestDrtSystem
import test.feeds.test.CSVFixtures
import test.roles.MockRoles
import test.roles.MockRoles.MockRolesProtocol._
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Success

@Singleton
class TestController @Inject()(val config: Configuration) extends InjectedController with AirportConfProvider {
  implicit val timeout: Timeout = Timeout(5 second)

  implicit val ec: ExecutionContextExecutor = DrtActorSystem.ec

  val log: Logger = LoggerFactory.getLogger(getClass)

  def ctrl: TestDrtSystem = DrtActorSystem.drtTestSystem

  val baseTime: SDateLike = SDate.now()

  def saveArrival(arrival: Arrival): Future[Any] = {
    log.info(s"Incoming test arrival")
    ctrl.testArrivalActor.ask(arrival).map { _ =>
      ctrl.liveActor ! AdhocCheck
    }
  }

  def saveVoyageManifest(voyageManifest: VoyageManifest): Future[Any] = {
    log.info(s"Sending Splits: ${voyageManifest.EventCode} to Test Actor")
    ctrl.testManifestsActor.ask(VoyageManifests(Set(voyageManifest)))
  }

  def resetData(): Future[Any] = {
    log.info(s"Sending reset message")
    ctrl.restartActor.ask(ResetData)
  }

  def addArrival(): Action[AnyContent] = Action.async {
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
            PassengerSources = Map(LiveFeedSource -> Passengers(actPax,if (actPax.isEmpty) None else Option(flight.TranPax))),
            Scheduled = SDate(flight.SchDT).millisSinceEpoch
          )
          saveArrival(arrival).map(_ => Created)
        case None =>
          Future(BadRequest(s"Unable to parse JSON: ${request.body.asText}"))
      }
  }

  def addArrivals(forDate: String): Action[AnyContent] = Action.async {
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

  def addManifest(): Action[AnyContent] = Action.async {
    request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[VoyageManifest]) match {
        case Some(vm) =>
          log.info(s"Got a manifest to save ${vm.CarrierCode}${vm.VoyageNumber} ${vm.ScheduledDateOfArrival} ${vm.ScheduledTimeOfArrival}")
          saveVoyageManifest(vm).map(_ => Created)
        case None =>
          Future(BadRequest(s"Unable to parse JSON: ${request.body.asText}"))
      }
  }

  def setMockRoles(): Action[AnyContent] = Action {
    implicit request =>
      request.body.asJson.map(s => s.toString.parseJson.convertTo[MockRoles]) match {
        case Some(roles) =>
          log.info(s"Got mock roles to set: $roles")

          log.info(s"Replacing these mock roles: ${request.session.data}")
          log.info(s"mock headers: ${request.headers}")

          Created.withSession(Session(Map("mock-roles" -> roles.roles.map(_.name).mkString(","))))
        case None =>
          BadRequest(s"Unable to parse JSON: ${request.body.asText}")
      }
  }

  def setMockRolesByQueryString(): Action[AnyContent] = Action {
    implicit request =>
      request.queryString.get("roles") match {
        case Some(rs) =>
          Redirect("/").withSession(Session(Map("mock-roles" -> rs.mkString(","))))
        case roles =>
          BadRequest(s"""Unable to parse roles: $roles from query string ${request.queryString}""")
      }
  }

  def deleteAllData(): Action[AnyContent] = Action.async { _ =>
    resetData().map(_ => Accepted)
  }
}
