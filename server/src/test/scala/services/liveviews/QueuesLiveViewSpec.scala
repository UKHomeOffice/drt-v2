package services.liveviews

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.db.AggregateDbH2
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.{PortCode, Queues}
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, QueueDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class QueuesLiveViewSpec extends AnyWordSpec with Matchers {
  private val system = ActorSystem("QueuesLiveViewSpec")
  implicit val mat: Materializer = Materializer(system)

  "QueuesLiveView" should {
    "update queues live view and remove any existing rows for the same port, terminal and slot time" in {
      val dao = QueueSlotDao()
      val aggDb = AggregateDbH2
      aggDb.dropAndCreateH2Tables()
      val portCode = PortCode("LHR")

      val updateQueuesLiveView = QueuesLiveView.updateQueuesLiveView(dao, aggDb, portCode)

      val date = UtcDate(2023, 10, 1)
      val startDate = SDate(date)

      val minutesToUpdate = Seq(
        CrunchMinute(T1, EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
        CrunchMinute(T1, NonEeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
      )

      Await.result(updateQueuesLiveView(date, minutesToUpdate), 1.second)

      val queues = queuesForPortAndDate(dao, aggDb, portCode, date)

      queues should ===(Set(EeaDesk, NonEeaDesk))

      val minutesToUpdate2 = Seq(
        CrunchMinute(T1, QueueDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
      )

      Await.result(updateQueuesLiveView(date, minutesToUpdate2), 1.second)

      val minutesForDate2 = queuesForPortAndDate(dao, aggDb, portCode, date)

      minutesForDate2 should ===(Set(QueueDesk))

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
