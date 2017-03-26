package drt.client.services

import diode.data._
import drt.client.components.TerminalDeploymentsTable.{QueueDeploymentsRow, TerminalDeploymentsRow}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object TerminalDepolymentTests extends TestSuite {

  import drt.client.TableViewUtils._
  
  val airportConfig = AirportConfig(
    "STN",
    Map("T1" -> Seq("eeaDesk", "nonEeaDesk", "eGate")),
    Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20),
    Seq("T1"),
    SplitRatios(List(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.4875))),
    Map("T1" -> Map(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 20d / 60))
  )

  def tests = TestSuite {

    "Given crunch results and simulation results for one minute in one queue, when we ask for a TerminalDeploymentsRow " +
      "then we should see the data for that minute" - {
      val workload = Map("eeaDesk" -> List(5.0))
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1), Seq(10))))
      )
      val simulationResult = Map(
        "eeaDesk" ->
          Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5)))
      )

      val result = terminalDeploymentsRows("T1", Ready(airportConfig), Seq(0L), workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(TerminalDeploymentsRow(0L, Seq(
        QueueDeploymentsRow(0, pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5, "eeaDesk"))))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 2 queues, when we ask for a TerminalDeploymentsRow " +
      "then we should see the data for that minute from both queues" - {
      val workload = Map(
        "eeaDesk" -> List(5.0),
        "eGate" -> List(6.0)
      )
      val queueCrunchResults = Map(
        "eeaDesk" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1), Seq(10)))),
        "eGate" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2), Seq(20)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10))))

      val result = terminalDeploymentsRows("T1", Ready(airportConfig), Seq(0L), workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(TerminalDeploymentsRow(0L, Seq(
        QueueDeploymentsRow(0, pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5, "eeaDesk"),
        QueueDeploymentsRow(0, pax = 6, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10, "eGate")
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 3 queues, when we ask for a TerminalDeploymentsRow " +
      "then we should see the data for that minute from all 3 queues" - {
      val workload = Map(
        "eeaDesk" -> List(5.0),
        "nonEeaDesk" -> List(6.0),
        "eGate" -> List(7.0)
      )
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1), Seq(10)))),
        "nonEeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1), Seq(10)))),
        "eGate" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2), Seq(20)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "nonEeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10))))

      val result = terminalDeploymentsRows("T1", Ready(airportConfig), Seq(0L), workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(TerminalDeploymentsRow(0L, Seq(
        QueueDeploymentsRow(0, pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5, "eeaDesk"),
        QueueDeploymentsRow(0, pax = 6, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5, "nonEeaDesk"),
        QueueDeploymentsRow(0, pax = 7, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10, "eGate")
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 1 queue, when we ask for a TerminalDeploymentsRow " +
      "then we should see the highest values for those minutes" - {
      val workload = Map(
        "eeaDesk" -> List(5.0)
      )
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2, 1), Seq(10, 20)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10))))

      val result = terminalDeploymentsRows("T1", Ready(airportConfig), List(0L, 60000L), workload, queueCrunchResults, simulationResult, Map())

      val expected = List(TerminalDeploymentsRow(0L, Seq(
        QueueDeploymentsRow(0, pax = 5, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10, "eeaDesk")
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 3 queues, when we ask for a TerminalDeploymentsRow " +
      "then we should see the highest values from each queue for those minutes" - {
      val workload = Map(
        "eeaDesk" -> List(5.0, 1.0),
        "eGate" -> List(6.0, 1.0),
        "nonEeaDesk" -> List(7.0, 1.0)
      )
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, recommendedDesks = IndexedSeq(2, 1), waitTimes = Seq(10, 20)))),
        "eGate" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(23, 3), Seq(20, 27)))),
        "nonEeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(15, 21), Seq(34, 23)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 5), DeskRec(1, 7)), Seq(15, 25))),
        "nonEeaDesk" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 11), DeskRec(1, 8)), Seq(30, 14))))

      val result = terminalDeploymentsRows("T1", Ready(airportConfig), List(0L, 60000L), workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(TerminalDeploymentsRow(0L, Seq(
        QueueDeploymentsRow(0, pax = 6, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10, "eeaDesk"),
        QueueDeploymentsRow(0, pax = 8, crunchDeskRec = 21, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 34, waitTimeWithUserDeskRec = 30, "nonEeaDesk"),
        QueueDeploymentsRow(0, pax = 7, crunchDeskRec = 23, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 27, waitTimeWithUserDeskRec = 25, "eGate")
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 16 minutes in 1 queue, when we ask for TerminalDeploymentsRows " +
      "then we should see the highest values for those minutes" - {
      val workload = Map(
        "eeaDesk" -> List(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0)
      )
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3), Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(
          DeskRec(0, 1), DeskRec(1, 2),
          DeskRec(2, 1), DeskRec(3, 2),
          DeskRec(4, 8), DeskRec(5, 2),
          DeskRec(6, 1), DeskRec(7, 2),
          DeskRec(8, 1), DeskRec(9, 11),
          DeskRec(10, 1), DeskRec(11, 2),
          DeskRec(12, 1), DeskRec(13, 2),
          DeskRec(14, 1), DeskRec(15, 5)
        ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 15, 1, 1, 1, 1, 9))))

      val timestamps = 0L to (60000 * 16) by 60000L
      val result = terminalDeploymentsRows("T1", Ready(airportConfig), timestamps, workload, queueCrunchResults, simulationResult, userDeskRec = Map())

      val expected = Seq(
        TerminalDeploymentsRow(0L, Seq(QueueDeploymentsRow(0L, pax = 75, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15, "eeaDesk"))),
        TerminalDeploymentsRow(15L * 60000, Seq(QueueDeploymentsRow(15L * 60000, pax = 5, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot(15L * 60000, 0), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9, "eeaDesk")))
      )

      assert(expected == result)
    }

    "Given crunch results and simulation results for 32 minutes in 1 queue, when we ask for TerminalDeploymentsRows " +
      "then we should see the highest values for those minutes" - {
      val workload = Map("eeaDesk" -> List(
        5.0, 1.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 6.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,
        5.0, 5.0,

        5.0, 7.0
      ))
      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3, 1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3),
            Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4, 1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4))
          )))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(
          DeskRec(0, 1), DeskRec(1, 2),
          DeskRec(2, 1), DeskRec(3, 2),
          DeskRec(4, 8), DeskRec(5, 2),
          DeskRec(6, 1), DeskRec(7, 2),
          DeskRec(8, 1), DeskRec(9, 11),
          DeskRec(10, 1), DeskRec(11, 2),
          DeskRec(12, 1), DeskRec(13, 2),
          DeskRec(14, 1), DeskRec(15, 5),
          DeskRec(16, 1), DeskRec(17, 2),
          DeskRec(18, 1), DeskRec(19, 2),
          DeskRec(20, 8), DeskRec(21, 2),
          DeskRec(22, 1), DeskRec(23, 2),
          DeskRec(24, 1), DeskRec(25, 11),
          DeskRec(26, 1), DeskRec(27, 2),
          DeskRec(28, 1), DeskRec(29, 2),
          DeskRec(30, 1), DeskRec(31, 5)
        ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 15, 1, 1, 1, 1, 9, 1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 14, 1, 1, 1, 1, 9))))

      val timestamps = 0L to (60000 * 32) by 60000L
      val result = terminalDeploymentsRows("T1", Ready(airportConfig), timestamps, workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(
        TerminalDeploymentsRow(0L, Seq(QueueDeploymentsRow(0L, pax = 71, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15, "eeaDesk"))),
        TerminalDeploymentsRow(15L * 60000, Seq(QueueDeploymentsRow(15L * 60000, pax = 76, crunchDeskRec = 9, userDeskRec = DeskRecTimeslot(15L * 60000, 0), waitTimeWithCrunchDeskRec = 9, waitTimeWithUserDeskRec = 14, "eeaDesk"))),
        TerminalDeploymentsRow(30L * 60000, Seq(QueueDeploymentsRow(30L * 60000, pax = 12, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot(30L * 60000, 0), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9, "eeaDesk")))
      )

      assert(expected == result)
    }

    "Given crunch results and simulation results for 16 minutes in 2 queues, when we ask for TerminalDeploymentsRows " +
      "then we should see the highest values for those minutes in each queue" - {
      val workload = Map(
        "eeaDesk" -> List(
          1.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 9.0),
        "nonEeaDesk" -> List(
          2.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 5.0,
          5.0, 11.0)
      )

      val queueCrunchResults = Map(
        "eeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3), Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4)))),
        "nonEeaDesk" ->
          Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4), Seq(1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 5)))))

      val simulationResult = Map(
        "eeaDesk" -> Ready(SimulationResult(IndexedSeq(
          DeskRec(0, 1), DeskRec(1, 2),
          DeskRec(2, 1), DeskRec(3, 2),
          DeskRec(4, 8), DeskRec(5, 2),
          DeskRec(6, 1), DeskRec(7, 2),
          DeskRec(8, 1), DeskRec(9, 11),
          DeskRec(10, 1), DeskRec(11, 2),
          DeskRec(12, 1), DeskRec(13, 2),
          DeskRec(14, 1), DeskRec(15, 5)
        ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 15, 1, 1, 1, 1, 9))),
        "nonEeaDesk" -> Ready(SimulationResult(IndexedSeq(
          DeskRec(0, 1), DeskRec(1, 2),
          DeskRec(2, 1), DeskRec(3, 2),
          DeskRec(4, 8), DeskRec(5, 2),
          DeskRec(6, 1), DeskRec(7, 2),
          DeskRec(8, 1), DeskRec(9, 12),
          DeskRec(10, 1), DeskRec(11, 2),
          DeskRec(12, 1), DeskRec(13, 2),
          DeskRec(14, 1), DeskRec(15, 5)
        ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 16, 1, 1, 1, 1, 10)))
      )

      val timestamps = 0L to (60000 * 16) by 60000L
      val result = terminalDeploymentsRows("T1", Ready(airportConfig), timestamps, workload, queueCrunchResults, simulationResult, Map())

      val expected = Seq(
        TerminalDeploymentsRow(0L, Seq(
          QueueDeploymentsRow(0L, pax = 71, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15, "eeaDesk"),
          QueueDeploymentsRow(0L, pax = 72, crunchDeskRec = 8, userDeskRec = DeskRecTimeslot(0, 0), waitTimeWithCrunchDeskRec = 9, waitTimeWithUserDeskRec = 16, "nonEeaDesk"))),
        TerminalDeploymentsRow(15L * 60000, Seq(
          QueueDeploymentsRow(15L * 60000, pax = 9, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot(15L * 60000, 0), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9, "eeaDesk"),
          QueueDeploymentsRow(15L * 60000, pax = 11, crunchDeskRec = 4, userDeskRec = DeskRecTimeslot(15L * 60000, 0), waitTimeWithCrunchDeskRec = 5, waitTimeWithUserDeskRec = 10, "nonEeaDesk")
        ))
      )

      assert(expected == result)
    }
  }
}
