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
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T2, T3, Terminal}
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

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

  private def newController(interface: DrtSystemInterface) =
    new SummariesController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface = new TestDrtModule(Lhr.config).provideDrtSystemInterface
}
