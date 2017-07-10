package drt.client.services

import drt.client.DeskStats
import drt.client.components.TerminalDeploymentsTable.QueueDeploymentsRowEntry
import drt.shared.FlightsApi.QueueName
import drt.shared.{DeskStat, Queues}
import utest._

import scala.collection.immutable.Map

object DeskstatsTests extends TestSuite {

  def tests = TestSuite {
    "Given a list of 1 QueueDeploymentsRowEntry " +
      "When we ask for it withActDesks and we don't have any act desk data" +
      "Then we should see the the QueueDeploymentsRowEntry untouched" - {
      val desk = Queues.EeaDesk
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, desk)
      )

      val actDeskNos = Map[QueueName, Map[Long, DeskStat]]()

      val queueRowsWithActs = {
        DeskStats.withActuals(queueRows, actDeskNos)
      }

      val expected: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, desk)
      )

      assert(queueRowsWithActs == expected)
    }

    "Given a list of 1 QueueDeploymentsRowEntry " +
      "When we ask for it withActDesks " +
      "Then we should see the act desk option added to it" - {
      val desk = Queues.EeaDesk
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, desk)
      )

      val actDeskNos = Map(desk -> Map(ten00 -> DeskStat(Option(5), None)))

      val queueRowsWithActs = {
        DeskStats.withActuals(queueRows, actDeskNos)
      }

      val expected: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(5), 1, 2, None, desk)
      )

      assert(queueRowsWithActs == expected)
    }

    "Given a list of 2 QueueDeploymentsRowEntrys, for different queues" +
      "When we ask for them withActDesks " +
      "Then we should see the appropriate act desk option added to them" - {
      val eea = Queues.EeaDesk
      val egate = Queues.EGate
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, egate)
      )

      val actDeskNos = Map(
        eea -> Map(ten00 -> DeskStat(Option(5), None)),
        egate -> Map(ten00 -> DeskStat(Option(2), None))
      )

      val queueRowsWithActs = {
        DeskStats.withActuals(queueRows, actDeskNos)
      }

      val expected: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(5), 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(2), 1, 2, None, egate)
      )

      assert(queueRowsWithActs == expected)
    }

    "Given a list of 2 QueueDeploymentsRowEntrys, for different queues and different times" +
      "When we ask for them withActDesks " +
      "Then we should see the appropriate act desk option added to them" - {
      val eea = Queues.EeaDesk
      val egate = Queues.EGate
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val ten15 = 1559816100000L // 2017-06-30 10:15 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, egate),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), None, 1, 2, None, egate)
      )

      val actDeskNos = Map(
        eea -> Map(ten00 -> DeskStat(Option(5), None), ten15 -> DeskStat(Option(6), None)),
        egate -> Map(ten00 -> DeskStat(Option(2), None), ten15 -> DeskStat(Option(3), None))
      )

      val result = {
        DeskStats.withActuals(queueRows, actDeskNos)
      }

      val expected: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(5), 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(2), 1, 2, None, egate),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), Some(6), 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), Some(3), 1, 2, None, egate)
      )

      assert(result == expected)
    }

    "Given a list of 2 QueueDeploymentsRowEntrys, for different queues and different times" +
      "When we ask for them withActDesks but we don't have them all " +
      "Then we should see the appropriate act desk option added to them" - {
      val eea = Queues.EeaDesk
      val egate = Queues.EGate
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val ten15 = 1559816100000L // 2017-06-30 10:15 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, egate),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), None, 1, 2, None, egate)
      )

      val actDeskNos = Map(
        eea -> Map(ten00 -> DeskStat(Option(5), None)),
        egate -> Map(ten15 -> DeskStat(Option(3), None))
      )

      val result = {
        DeskStats.withActuals(queueRows, actDeskNos)
      }

      val expected: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), Some(5), 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, egate),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), None, 1, 2, None, eea),
        QueueDeploymentsRowEntry(ten15, 10, 1, DeskRecTimeslot(ten15, 2), Some(3), 1, 2, None, egate)
      )

      assert(result == expected)
    }
  }
}
