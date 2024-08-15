package controllers.application

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.Materializer
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.Helpers._
import play.api.test._
import slick.jdbc.H2Profile.api._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.dao.{CapacityHourlyDao, PassengersHourlyDao}
import uk.gov.homeoffice.drt.db.tables.CapacityHourlyRow
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, T3, Terminal}
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.sql.Timestamp
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class SummariesControllerSpec extends PlaySpec with BeforeAndAfterEach {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  val schemas = Seq(CapacityHourlyDao.table.schema, PassengersHourlyDao.table.schema)

  override def beforeEach(): Unit = {
    schemas.map { schema =>
      Await.ready(AggregateDbH2.db.run(DBIO.seq(schema.dropIfExists, schema.createIfNotExists)), 10.second)
    }
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
  private val terminals: Seq[Terminal] = Seq(T2, T3)

  private val portPaxPerDay: Int = terminalPaxPerDay * terminals.size
  private val capacity: Int = 100

  "populatePassengersForDate" should {
    "create hourly entries from existing crunch minutes for the date requested" in {
      val drtInterface = newDrtInterface
      val minutes = MinutesContainer(generateMinutes(SDate("2024-05-31T23:00"), SDate("2024-06-01T22:59"), Seq(T3, T2), queues, queuePaxPerMinute))

      Await.ready(drtInterface.minuteLookups.queueMinutesRouterActor.ask(minutes), 5.second)
      val controller = newController(drtInterface)

      val authHeader = Headers(("X-Forwarded-Groups" -> "super-admin,LHR"))
      val result = controller
        .populatePassengersForDate("2024-06-01")
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)

      val hourlyForLhrT3 = PassengersHourlyDao.hourlyForPortAndDate("LHR", Option("T3"))
      val rows = Await.result(AggregateDbH2.db.run(hourlyForLhrT3(LocalDate(2024, 6, 1))), 5.second)
      rows must ===((0 to 23).map { hour =>
        (SDate("2024-06-01", europeLondonTimeZone).addHours(hour).millisSinceEpoch, Map(EeaDesk -> queuePaxPerHour, NonEeaDesk -> queuePaxPerHour))
      }.toMap)
    }
  }

  "exportPassengersByTerminalForDateRangeApi" should {
    val acceptHeader = Headers(("Accept", "text/csv"), "X-Forwarded-Groups" -> "LHR")
    "generate a csv with the correct headers for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(s"Heathrow,LHR,$capacity,$portPaxPerDay,0,0,$terminalPaxPerDay,$terminalPaxPerDay,0\n")
    }
    "generate a daily breakdown csv with the correct headers for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=daily", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-02").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        s"""2024-06-01,Heathrow,LHR,$capacity,$portPaxPerDay,0,0,$terminalPaxPerDay,$terminalPaxPerDay,0
           |2024-06-02,Heathrow,LHR,0,0,0,0,0,0,0
           |""".stripMargin)
    }
    "generate an hourly breakdown csv with the correct headers for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=hourly", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-02").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        (0 to 23).map { hour =>
          val capacity = if (hour == 13) 100 else 0
          f"2024-06-01T$hour%02d:00:00+01:00,Heathrow,LHR,$capacity,${portPaxPerDay / 24},0,0,${terminalPaxPerDay / 24},${terminalPaxPerDay / 24},0\n"
        }.mkString
      )
    }
    "generate a csv with the correct headers for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(s"Heathrow,LHR,T3,0,${queuePaxPerDay * queues.size},0,0,$queuePaxPerDay,$queuePaxPerDay,0\n")
    }
    "generate a daily breakdown csv with the correct headers for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=daily", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-02", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        s"""2024-06-01,Heathrow,LHR,T3,0,${queuePaxPerDay * queues.size},0,0,$queuePaxPerDay,$queuePaxPerDay,0
           |2024-06-02,Heathrow,LHR,T3,0,0,0,0,0,0,0
           |""".stripMargin)
    }
    "generate an hourly breakdown csv with the correct headers for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=hourly", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-02", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        (0 to 23).map { hour =>
          f"2024-06-01T$hour%02d:00:00+01:00,Heathrow,LHR,T3,0,${queuePaxPerHour * queues.size},0,0,$queuePaxPerHour,$queuePaxPerHour,0\n"
        }.mkString
      )
    }
  }

  "exportPassengersByTerminalForDateRangeApi" should {
    val acceptHeader = Headers(("Accept", "application/json"), ("X-Forwarded-Groups" -> "LHR"))
    "generate a json response for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      val totalPax = queuePaxPerDay * queues.size * terminals.size
      val queuePax = queuePaxPerDay * terminals.size
      contentAsString(result) must ===(s"""[{"portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":$queuePax},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":$queuePax}],"regionName":"Heathrow","totalCapacity":$capacity,"totalPcpPax":$totalPax}]""")
    }
    "generate a daily breakdown json response for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=daily", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      contentAsString(result) must ===(s"""[{"date":"2024-06-01","portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":$terminalPaxPerDay},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":$terminalPaxPerDay}],"regionName":"Heathrow","totalCapacity":$capacity,"totalPcpPax":$portPaxPerDay}]""")
    }
    "generate a hourly breakdown json response for the given port" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=hourly", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByPortForDateRangeApi("2024-06-01", "2024-06-01").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      val hourlyContent = (0 to 23).map { hour =>
        val cap = if (hour == 13) capacity else 0
        s"""{"date":"2024-06-01","hour":$hour,"portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":${terminalPaxPerDay / 24}},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":${terminalPaxPerDay / 24}}],"regionName":"Heathrow","totalCapacity":$cap,"totalPcpPax":${portPaxPerDay / 24}}"""
      }.mkString(",")
      contentAsString(result) must ===(s"[$hourlyContent]")
    }
    "generate a json response for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      contentAsString(result) must ===(s"""[{"portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":$queuePaxPerDay},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":$queuePaxPerDay}],"regionName":"Heathrow","terminalName":"T3","totalCapacity":0,"totalPcpPax":${queuePaxPerDay * queues.size}}]""")
    }
    "generate a daily breakdown json response for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=daily", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      contentAsString(result) must ===(s"""[{"date":"2024-06-01","portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":$queuePaxPerDay},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":$queuePaxPerDay}],"regionName":"Heathrow","terminalName":"T3","totalCapacity":0,"totalPcpPax":${queuePaxPerDay * queues.size}}]""")
    }
    "generate a hourly breakdown json response for the given terminal" in {
      val controller: SummariesController = populateForDate(LocalDate(2024, 6, 1), terminals)
      val csvRequest = FakeRequest(method = "GET", uri = "?granularity=hourly", headers = acceptHeader, body = AnyContentAsEmpty)
      val result = controller.exportPassengersByTerminalForDateRangeApi("2024-06-01", "2024-06-01", "T3").apply(csvRequest)

      status(result) must ===(OK)
      contentType(result) must ===(Some("application/json"))
      val hourlyContent = (0 to 23).map { hour =>
        s"""{"date":"2024-06-01","hour":$hour,"portCode":"LHR","queueCounts":[{"queueName":"EeaDesk","queueDisplayName":"EEA","count":$queuePaxPerHour},{"queueName":"NonEeaDesk","queueDisplayName":"Non-EEA","count":$queuePaxPerHour}],"regionName":"Heathrow","terminalName":"T3","totalCapacity":0,"totalPcpPax":${queuePaxPerHour * queues.size}}"""
      }.mkString(",")
      contentAsString(result) must ===(s"[$hourlyContent]")
    }
  }

  private def populateForDate(localDate: LocalDate, terminals: Seq[Terminal]): SummariesController = {
    val startSDate = SDate(localDate)
    val endSDate = SDate(localDate).addDays(1).addMinutes(-1)
    val drtInterface = newDrtInterface
    val db = drtInterface.applicationService.aggregatedDb
    val replaceHours = CapacityHourlyDao.replaceHours(drtInterface.airportConfig.portCode)
    val row = CapacityHourlyRow(drtInterface.airportConfig.portCode.iata, T1.toString, startSDate.toLocalDate.toISOString, 12, capacity, new Timestamp(0L))
    val evHours = replaceHours(T1, Seq(row))
    Await.ready(db.run(evHours), 1.second)

    val minutes = MinutesContainer(generateMinutes(startSDate, endSDate, terminals, queues, queuePaxPerMinute))
    Await.ready(drtInterface.minuteLookups.queueMinutesRouterActor.ask(minutes), 1.second)

    val controller = newController(drtInterface)

    val request = FakeRequest(method = "PUT", uri = "", headers = Headers(("X-Forwarded-Groups", "super-admin,LHR")), body = AnyContentAsEmpty)
    Await.ready(controller.populatePassengersForDate(localDate.toISOString).apply(request), 1.second)
    controller
  }

  private def newController(interface: DrtSystemInterface) =
    new SummariesController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface = new TestDrtModule(Lhr.config).provideDrtSystemInterface
}
