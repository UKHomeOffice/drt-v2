package controllers.application

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.Materializer
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import module.DRTModule
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.Helpers._
import play.api.test._
import services.crunch.H2Tables
import services.graphstages.Crunch
import slick.jdbc.H2Profile.api._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T2, T3, Terminal}
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class SummariesControllerSpec extends PlaySpec with BeforeAndAfterEach {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  val schema = PassengersHourlyDao.table.schema

  override def beforeEach(): Unit = {
    Await.ready(H2Tables.db.run(DBIO.seq(schema.dropIfExists, schema.create)), 1.second)
  }

  def generateMinutes(start: SDateLike, end: SDateLike, terminals: Seq[Terminal], queues: Seq[Queue], paxPerHour: Double): Seq[CrunchMinute] = {
    (start.millisSinceEpoch to end.millisSinceEpoch by 60000L)
      .flatMap(millis => terminals.flatMap(terminal => queues.map(queue => (terminal, queue, millis))))
      .map {
        case (terminal, queue, millis) => CrunchMinute(terminal, queue, millis, paxPerHour, 0d, 0, 0, None, None, None, None, None, None, None)
      }
  }

  private val queues: Seq[Queue] = Seq(EeaDesk, NonEeaDesk)
  private val queuePaxPerMinute = 10
  private val queuePaxPerHour = queuePaxPerMinute * 60
  private val queuePaxPerDay = queuePaxPerHour * 24
  private val terminalPaxPerDay = queuePaxPerDay * queues.size

  "populatePassengersForDate" should {
    "create hourly entries from existing crunch minutes for the date requested" in {
      val drtInterface = newDrtInterface
      val minutes = MinutesContainer(generateMinutes(SDate("2024-05-31T23:00"), SDate("2024-06-01T22:59"), Seq(T3, T2), queues, queuePaxPerMinute))

      Await.ready(drtInterface.minuteLookups.queueMinutesRouterActor.ask(minutes), 1.second)
      val controller = newController(drtInterface)

      val result = controller.populatePassengersForDate("2024-06-01").apply(FakeRequest())

      status(result) must ===(OK)

      val function = PassengersHourlyDao.hourlyForPortAndDate("LHR", Option("T3"))
      val rows = Await.result(H2Tables.db.run(function(LocalDate(2024, 6, 1))), 1.second)
      rows must ===((0 to 23).map { hour =>
        (SDate("2024-06-01", Crunch.europeLondonTimeZone).addHours(hour).millisSinceEpoch, Map(EeaDesk -> queuePaxPerHour, NonEeaDesk -> queuePaxPerHour))
      }.toMap)
    }
  }

  "exportPassengersByTerminalForDateRangeApi" should {
    "generate a csv with the correct headers for the given port" in {
      val terminals = Seq(T2, T3)
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = Headers(("Content-Type", "text/csv")), body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(s"Heathrow,LHR,${terminalPaxPerDay * terminals.size},0,0,${queuePaxPerDay * terminals.size},${queuePaxPerDay * terminals.size},0\n")
    }
    "generate a csv with the correct headers for the given terminal" in {
      val terminals = Seq(T2, T3)
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = Headers(("Content-Type", "text/csv")), body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(s"Heathrow,LHR,T3,${queuePaxPerDay * queues.size},0,0,$queuePaxPerDay,$queuePaxPerDay,0\n")
    }
  }

  "exportPassengersByTerminalForDateRangeApi" should {
    "generate a json response for the given port" in {
      val terminals = Seq(T2, T3)
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = Headers(("Content-Type", "application/json")), body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      val totalPax = queuePaxPerDay * queues.size * terminals.size
      val queuePax = queuePaxPerDay * terminals.size
      contentAsString(result) must ===(s"""[{"portCode":"LHR","queueCounts":[{"queueName":"EEA","count":$queuePax},{"queueName":"Non-EEA","count":$queuePax}],"regionName":"Heathrow","totalPcpPax":$totalPax}]""")
    }
    "generate a json response for the given terminal" in {
      val terminals = Seq(T2, T3)
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = Headers(("Content-Type", "application/json")), body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      contentAsString(result) must ===(s"""[{"portCode":"LHR","queueCounts":[{"queueName":"EEA","count":$queuePaxPerDay},{"queueName":"Non-EEA","count":$queuePaxPerDay}],"regionName":"Heathrow","terminalName":"T3","totalPcpPax":${queuePaxPerDay * queues.size}}]""")
    }
  }

  private def populateForDate(localDate: LocalDate, terminals: Seq[Terminal]) = {
    val startSDate = SDate(localDate)
    val endSDate = SDate(localDate).addDays(1).addMinutes(-1)
    val drtInterface = newDrtInterface
    val minutes = MinutesContainer(generateMinutes(startSDate, endSDate, terminals, queues, queuePaxPerMinute))

    Await.ready(drtInterface.minuteLookups.queueMinutesRouterActor.ask(minutes), 1.second)
    val controller = newController(drtInterface)

    Await.ready(controller.populatePassengersForDate(localDate.toISOString).apply(FakeRequest()), 1.second)
    controller
  }

  private def newController(interface: DrtSystemInterface) =
    new SummariesController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface =
    new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val airportConfig: AirportConfig = Lhr.config
    }.provideDrtSystemInterface
}
