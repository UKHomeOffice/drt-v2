package drt.client.services


import drt.client.DeskStats
import drt.client.components.TerminalDeploymentsTable.QueueDeploymentsRowEntry
import drt.shared.Queues
import utest._

import scala.collection.immutable.Map

object DeskstatsTests extends TestSuite {

  def tests = TestSuite {
    "Given a list of 1 QueueDeploymentsRowEntry " +
      "When we ask for it withActDesks " +
      "Then we should see the act desk option added to it" - {
      val desk = Queues.EeaDesk
      val ten00 = 1559815200000L // 2017-06-30 10:00 UTC
      val queueRows: List[QueueDeploymentsRowEntry] = List(
        QueueDeploymentsRowEntry(ten00, 10, 1, DeskRecTimeslot(ten00, 2), None, 1, 2, None, desk)
      )

      val terminal = "T2"
      val actDeskNos = Map(terminal -> Map(desk -> Map(ten00 -> Option(5))))

      val queueRowsWithActs = {
        DeskStats.withActDesks(queueRows, actDeskNos(terminal))
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

      val terminal = "T2"
      val actDeskNos = Map(terminal -> Map(
        eea -> Map(ten00 -> Option(5)),
        egate -> Map(ten00 -> Option(2))
      ))

      val queueRowsWithActs = {
        DeskStats.withActDesks(queueRows, actDeskNos(terminal))
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

      val terminal = "T2"
      val actDeskNos = Map(terminal -> Map(
        eea -> Map(ten00 -> Option(5), ten15 -> Option(6)),
        egate -> Map(ten00 -> Option(2), ten15 -> Option(3))
      ))

      val result = {
        DeskStats.withActDesks(queueRows, actDeskNos(terminal))
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

      val terminal = "T2"
      val actDeskNos = Map(terminal -> Map(
        eea -> Map(ten00 -> Option(5)),
        egate -> Map(ten15 -> Option(3))
      ))

      val result = {
        DeskStats.withActDesks(queueRows, actDeskNos(terminal))
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