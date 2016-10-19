package spatutorial.client.services

import diode.data._
import spatutorial.client.components.TableTerminalDeskRecs.{QueueDetailsRow, TerminalUserDeskRecsRow}
import spatutorial.shared.FlightsApi._
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object TerminalUserDeskRecsTests extends TestSuite {

  import spatutorial.client.TableViewUtils._

  def tests = TestSuite {

    "Given crunch results and simulation results for one minute in one queue, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute" - {
      val workload: Map[QueueName, QueueWorkloads] = Map("EEA" -> ((Seq(), Seq(Pax(0, 5.0)))))
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1))))))
      )
      val simulationResult = Map(
        "EEA" ->
          Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5)))
      )

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5))))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 2 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute from both queues" - {
      val workload: Map[QueueName, QueueWorkloads] = Map(
        "EEA" -> ((Seq(), Seq(Pax(0, 5.0)))),
        "eGate" -> ((Seq(), Seq(Pax(0, 6.0))))
      )
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
        "eGate" ->
          Ready((Ready(CrunchResult(IndexedSeq(2), Seq(20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 2)))))))

      val simulationResult = Map(
        "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10))))

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(pax = 6, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 4), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 3 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute from all 3 queues" - {
      val workload: Map[QueueName, QueueWorkloads] = Map(
        "EEA" -> ((Seq(), Seq(Pax(0, 5.0)))),
        "Non-EEA" -> ((Seq(), Seq(Pax(0, 6.0)))),
        "eGate" -> ((Seq(), Seq(Pax(0, 7.0))))
      )
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
        "Non-EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
        "eGate" ->
          Ready((Ready(CrunchResult(IndexedSeq(2), Seq(20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 2)))))))

      val simulationResult = Map(
        "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "Non-EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10))))

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(pax = 5, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(pax = 6, crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(pax = 7, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 4), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 1 queue, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the highest values for those minutes" - {
      val workload: Map[QueueName, QueueWorkloads] = Map(
        "EEA" -> ((Seq(), Seq(Pax(0, 5.0))))
      )
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(2, 1), Seq(10, 20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2)))))))

      val simulationResult = Map(
        "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10))))

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(pax = 5, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 3 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the highest values from each queue for those minutes" - {
      val workload: Map[QueueName, QueueWorkloads] = Map(
        "EEA" -> ((Seq(), Seq(Pax(0, 5.0)))),
        "eGate" -> ((Seq(), Seq(Pax(0, 6.0)))),
        "Non-EEA" -> ((Seq(), Seq(Pax(0, 7.0)))))
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(2, 1), Seq(10, 20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2)))))),
        "eGate" ->
          Ready((Ready(CrunchResult(IndexedSeq(23, 3), Seq(20, 27))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 5), DeskRecTimeslot("1", 15)))))),
        "Non-EEA" ->
          Ready((Ready(CrunchResult(IndexedSeq(15, 21), Seq(34, 23))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 45), DeskRecTimeslot("1", 30)))))))

      val simulationResult = Map(
        "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10))),
        "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 5), DeskRec(1, 7)), Seq(15, 25))),
        "Non-EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 11), DeskRec(1, 8)), Seq(30, 14))))

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(pax = 5, crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10),
        QueueDetailsRow(pax = 6, crunchDeskRec = 23, userDeskRec = DeskRecTimeslot("0", 7), waitTimeWithCrunchDeskRec = 27, waitTimeWithUserDeskRec = 25),
        QueueDetailsRow(pax = 7, crunchDeskRec = 21, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 34, waitTimeWithUserDeskRec = 30)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 16 minutes in 1 queue, when we ask for TerminalUserDeskRecsRows " +
      "then we should see the highest values for those minutes" - {
      val workload: Map[QueueName, QueueWorkloads] = Map("EEA" -> ((Seq(), Seq(
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0),
        Pax(0, 5.0), Pax(0, 5.0)))))
      val queueCrunchResults = Map(
        "EEA" ->
          Ready((
            Ready(CrunchResult(IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3), Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4))),
            Ready(UserDeskRecs(Seq(
              DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2),
              DeskRecTimeslot("2", 1), DeskRecTimeslot("3", 2),
              DeskRecTimeslot("4", 8), DeskRecTimeslot("5", 2),
              DeskRecTimeslot("6", 1), DeskRecTimeslot("7", 2),
              DeskRecTimeslot("8", 1), DeskRecTimeslot("9", 11),
              DeskRecTimeslot("10", 1), DeskRecTimeslot("11", 2),
              DeskRecTimeslot("12", 1), DeskRecTimeslot("13", 2),
              DeskRecTimeslot("14", 1), DeskRecTimeslot("15", 5)
            ))))))

      val simulationResult = Map(
        "EEA" -> Ready(SimulationResult(IndexedSeq(
          DeskRec(0, 1), DeskRec(1, 2),
          DeskRec(2, 1), DeskRec(3, 2),
          DeskRec(4, 8), DeskRec(5, 2),
          DeskRec(6, 1), DeskRec(7, 2),
          DeskRec(8, 1), DeskRec(9, 11),
          DeskRec(10, 1), DeskRec(11, 2),
          DeskRec(12, 1), DeskRec(13, 2),
          DeskRec(14, 1), DeskRec(15, 5)
        ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 15, 1, 1, 1, 1, 9))))

      val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

      val expected = Seq(
        TerminalUserDeskRecsRow(0L, Seq(QueueDetailsRow(pax = 75, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15))),
        TerminalUserDeskRecsRow(15L, Seq(QueueDetailsRow(pax = 5, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot("15", 5), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9)))
      )

      assert(expected == result)
    }

        "Given crunch results and simulation results for 32 minutes in 1 queue, when we ask for TerminalUserDeskRecsRows " +
          "then we should see the highest values for those minutes" - {
          val workload: Map[QueueName, QueueWorkloads] = Map("EEA" -> ((Seq(), Seq(
            Pax(0, 5.0), Pax(0, 1.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0),

            Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 6.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),

            Pax(0, 5.0), Pax(0, 7.0)
          ))))
          val queueCrunchResults = Map(
            "EEA" ->
              Ready((
                Ready(CrunchResult(IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3, 1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3), Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4, 1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4))),
                Ready(UserDeskRecs(Seq(
                  DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2),
                  DeskRecTimeslot("2", 1), DeskRecTimeslot("3", 2),
                  DeskRecTimeslot("4", 8), DeskRecTimeslot("5", 2),
                  DeskRecTimeslot("6", 1), DeskRecTimeslot("7", 2),
                  DeskRecTimeslot("8", 1), DeskRecTimeslot("9", 11),
                  DeskRecTimeslot("10", 1), DeskRecTimeslot("11", 2),
                  DeskRecTimeslot("12", 1), DeskRecTimeslot("13", 2),
                  DeskRecTimeslot("14", 1), DeskRecTimeslot("15", 5),
                  DeskRecTimeslot("16", 1), DeskRecTimeslot("17", 2),
                  DeskRecTimeslot("18", 1), DeskRecTimeslot("19", 2),
                  DeskRecTimeslot("20", 8), DeskRecTimeslot("21", 2),
                  DeskRecTimeslot("22", 1), DeskRecTimeslot("23", 2),
                  DeskRecTimeslot("24", 1), DeskRecTimeslot("25", 11),
                  DeskRecTimeslot("26", 1), DeskRecTimeslot("27", 2),
                  DeskRecTimeslot("28", 1), DeskRecTimeslot("29", 2),
                  DeskRecTimeslot("30", 1), DeskRecTimeslot("31", 5)
                ))))))

          val simulationResult = Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(
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

          val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

          val expected = Seq(
            TerminalUserDeskRecsRow(0L, Seq(QueueDetailsRow(pax = 71, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15))),
            TerminalUserDeskRecsRow(15L, Seq(QueueDetailsRow(pax = 76, crunchDeskRec = 9, userDeskRec = DeskRecTimeslot("15", 11), waitTimeWithCrunchDeskRec = 9, waitTimeWithUserDeskRec = 14))),
            TerminalUserDeskRecsRow(30L, Seq(QueueDetailsRow(pax = 12, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot("30", 5), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9)))
          )

          assert(expected == result)
        }

        "Given crunch results and simulation results for 16 minutes in 2 queues, when we ask for TerminalUserDeskRecsRows " +
          "then we should see the highest values for those minutes in each queue" - {
          val workload: Map[QueueName, QueueWorkloads] = Map(
            "EEA" -> ((Seq(), Seq(
            Pax(0, 1.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 9.0)))),
            "Non-EEA" -> ((Seq(), Seq(
            Pax(0, 2.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 5.0),
            Pax(0, 5.0), Pax(0, 11.0))))
          )

          val queueCrunchResults = Map(
            "EEA" ->
              Ready((
                Ready(CrunchResult(IndexedSeq(1, 1, 1, 1, 1, 7, 1, 1, 1, 1, 5, 1, 1, 1, 1, 3), Seq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4))),
                Ready(UserDeskRecs(Seq(
                  DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2),
                  DeskRecTimeslot("2", 1), DeskRecTimeslot("3", 2),
                  DeskRecTimeslot("4", 8), DeskRecTimeslot("5", 2),
                  DeskRecTimeslot("6", 1), DeskRecTimeslot("7", 2),
                  DeskRecTimeslot("8", 1), DeskRecTimeslot("9", 11),
                  DeskRecTimeslot("10", 1), DeskRecTimeslot("11", 2),
                  DeskRecTimeslot("12", 1), DeskRecTimeslot("13", 2),
                  DeskRecTimeslot("14", 1), DeskRecTimeslot("15", 5)
                ))))),
            "Non-EEA" ->
              Ready((
                Ready(CrunchResult(IndexedSeq(1, 1, 1, 1, 1, 8, 1, 1, 1, 1, 5, 1, 1, 1, 1, 4), Seq(1, 1, 1, 1, 1, 9, 1, 1, 1, 1, 5, 1, 1, 1, 1, 5))),
                Ready(UserDeskRecs(Seq(
                  DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2),
                  DeskRecTimeslot("2", 1), DeskRecTimeslot("3", 2),
                  DeskRecTimeslot("4", 8), DeskRecTimeslot("5", 2),
                  DeskRecTimeslot("6", 1), DeskRecTimeslot("7", 2),
                  DeskRecTimeslot("8", 1), DeskRecTimeslot("9", 11),
                  DeskRecTimeslot("10", 1), DeskRecTimeslot("11", 2),
                  DeskRecTimeslot("12", 1), DeskRecTimeslot("13", 2),
                  DeskRecTimeslot("14", 1), DeskRecTimeslot("15", 5)
                ))))))

          val simulationResult = Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(
              DeskRec(0, 1), DeskRec(1, 2),
              DeskRec(2, 1), DeskRec(3, 2),
              DeskRec(4, 8), DeskRec(5, 2),
              DeskRec(6, 1), DeskRec(7, 2),
              DeskRec(8, 1), DeskRec(9, 11),
              DeskRec(10, 1), DeskRec(11, 2),
              DeskRec(12, 1), DeskRec(13, 2),
              DeskRec(14, 1), DeskRec(15, 5)
            ), Seq(1, 1, 1, 1, 1, 12, 1, 1, 1, 1, 15, 1, 1, 1, 1, 9))),
            "Non-EEA" -> Ready(SimulationResult(IndexedSeq(
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

          val result = terminalUserDeskRecsRows(workload, queueCrunchResults, simulationResult)

          val expected = Seq(
            TerminalUserDeskRecsRow(0L, Seq(
              QueueDetailsRow(pax = 71, crunchDeskRec = 7, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15),
              QueueDetailsRow(pax = 72, crunchDeskRec = 8, userDeskRec = DeskRecTimeslot("0", 12), waitTimeWithCrunchDeskRec = 9, waitTimeWithUserDeskRec = 16))),
            TerminalUserDeskRecsRow(15L, Seq(
              QueueDetailsRow(pax = 9, crunchDeskRec = 3, userDeskRec = DeskRecTimeslot("15", 5), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9),
              QueueDetailsRow(pax = 11, crunchDeskRec = 4, userDeskRec = DeskRecTimeslot("15", 5), waitTimeWithCrunchDeskRec = 5, waitTimeWithUserDeskRec = 10)
            ))
          )

          assert(expected == result)
        }
  }
}
