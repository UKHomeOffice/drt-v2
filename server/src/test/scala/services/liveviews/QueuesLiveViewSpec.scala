package services.liveviews

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.db.AggregateDbH2
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{PortCode, Queues}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class QueuesLiveViewSpec extends AnyWordSpec with Matchers with BeforeAndAfter {
  private val system = ActorSystem("QueuesLiveViewSpec")
  implicit val mat: Materializer = Materializer(system)

  val dao: QueueSlotDao = QueueSlotDao()
  val aggDb: AggregateDbH2.type = AggregateDbH2
  val portCode: PortCode = PortCode("LHR")
  val updateQueuesLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Int] =
    QueuesLiveView.updateQueuesLiveView(dao, aggDb, portCode)

  before {
    aggDb.dropAndCreateH2Tables()
  }

  "QueuesLiveView" should {
    val date = UtcDate(2023, 10, 1)
    val startDate = SDate(date)

    "update queues live view" in {
      val minutesToUpdate = Seq(
        CrunchMinute(T1, EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
        CrunchMinute(T1, NonEeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
      )

      Await.result(updateQueuesLiveView(date, minutesToUpdate), 1.second)

      val queues = queuesForPortAndDate(dao, aggDb, portCode, date)

      queues should ===(Set(EeaDesk, NonEeaDesk))
    }
  }

  private def queuesForPortAndDate(dao: QueueSlotDao, aggDb: AggregateDbH2.type, portCode: PortCode, date: UtcDate): Set[Queues.Queue] =
    Await.result(
      dao.queueSlotsForDateRange(portCode, 15, aggDb.run)(date, date, Seq(T1))
        .runWith(Sink.seq)
        .map(_.flatMap(_._2.map(_.queue)).toSet),
      1.second
    )
}
