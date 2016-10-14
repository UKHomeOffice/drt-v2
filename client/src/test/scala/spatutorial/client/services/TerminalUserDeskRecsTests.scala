package spatutorial.client.services

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.client.components.TableTerminalDeskRecs.{QueueDetailsRow, TerminalUserDeskRecsRow}
import spatutorial.client.services.HandyStuff.{CrunchResultAndDeskRecs, QueueUserDeskRecs}
import spatutorial.client.logger._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object TerminalUserDeskRecsTests extends TestSuite {
  def terminalUserDeskRecsRows(queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]], simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]]) = {
    val minute: Long = queueCrunchResults("A1").map(qcrr => {
      qcrr._2.get match {
        case (crunchResult, userDeskRec) => userDeskRec.get.items.map(_.id.toLong).min
      }
    }).min

    val queueNames = queueCrunchResults("A1").keys
    log.info(s"queue names: ${queueNames}")

    val queueRows: Seq[Seq[QueueDetailsRow]] = queueNames.toList.map(qn => {
      log.info(s"queue name: $qn")

      val stuff: Seq[Seq[Long]] = Seq(
        simulationResult("A1")(qn).get.recommendedDesks.map(rec => rec.time),
        queueCrunchResults("A1")(qn).get._1.get.recommendedDesks.map(_.toLong),
        simulationResult("A1")(qn).get.recommendedDesks.map(rec => rec.desks).map(_.toLong),
        queueCrunchResults("A1")(qn).get._1.get.waitTimes.map(_.toLong),
        simulationResult("A1")(qn).get.waitTimes.map(_.toLong)
      )

      log.info(s"stuff: $stuff")
      log.info(s"stuff transposed ${stuff.transpose}")

      stuff.transpose.map(thing => QueueDetailsRow(thing(1).toInt, DeskRecTimeslot(thing(0).toString, thing(2).toInt), thing(3).toInt, thing(4).toInt))
    })
    log.info(s"queue rows: ${queueRows}")

    queueRows.map((m: Seq[QueueDetailsRow]) => {
      TerminalUserDeskRecsRow(0L, m)
    })
  }

  def applyToEveryGroupOf15[A](f: A => Int, nos: Seq[A]): Unit = {
//    nos.grouped
  }

  def tests = TestSuite {
    "Given 16 minutes of desk recs" - {

    }

    "Given crunch results and simulation results for one minute in one queue, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute" - {
      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
        "A1" ->
          Map(
            "EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1))))))
          )
      )
      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
        "A1" ->
          Map(
            "EEA" ->
              Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5)))
          )
      )

      //val minMillis = queueCrunchResults.value("A1").map(qdrt => qdrt._2.get.items.map((drts: DeskRecTimeslot) => drts.id.toLong).min).min
      //val minMillis = 0
      //val dayOfMinutesInMillis = Seq.range(minMillis, minMillis + (60 * 60 * 24 * 1000), 60000)
      //      val rows2 = dayOfMinutesInMillis.map(milli => TerminalUserDeskRecsRow(milli, userDeskRecs.value("A1").map(qudrp => {
      //        val x: Seq[DeskRecTimeslot] = qudrp._2.get.items
      //        val y = x.filter(drts => drts.id.toLong == milli)
      //        //            QueueDetailsRow(x.map(drts => ))
      //      })))
      //      val stuff = userDeskRecs.value("A1").map((queueDeskRecsTuple: (String, Pot[UserDeskRecs])) => {
      //        val userDeskRecs = queueDeskRecsTuple._2.get
      //        userDeskRecs.items
      //      })

      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5))))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 2 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute from both queues" - {
      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
        "A1" -> Map(
            "EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
            "eGate" ->
              Ready((Ready(CrunchResult(IndexedSeq(2), Seq(20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 2))))))))

      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
        "A1" -> Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
            "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10)))))

      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 4), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for one minute in 3 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the data for that minute from all 3 queues" - {
      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
        "A1" -> Map(
            "EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
            "Non-EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(1), Seq(10))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1)))))),
            "eGate" ->
              Ready((Ready(CrunchResult(IndexedSeq(2), Seq(20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 2))))))))

      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
        "A1" -> Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
            "Non-EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2)), Seq(5))),
            "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 4)), Seq(10)))))

      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(crunchDeskRec = 1, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 10, waitTimeWithUserDeskRec = 5),
        QueueDetailsRow(crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 4), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 1 queue, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the highest values for those minutes" - {
      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
        "A1" -> Map(
            "EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(2, 1), Seq(10, 20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2))))))))

      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
        "A1" -> Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10)))))

      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10)
      )))

      assert(expected == result)
    }

    "Given crunch results and simulation results for 2 minutes in 3 queues, when we ask for a TerminalUserDeskRecsRow " +
      "then we should see the highest values from each queue for those minutes" - {
      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
        "A1" -> Map(
            "EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(2, 1), Seq(10, 20))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2)))))),
            "eGate" ->
              Ready((Ready(CrunchResult(IndexedSeq(23, 3), Seq(20, 27))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 5), DeskRecTimeslot("1", 15)))))),
            "Non-EEA" ->
              Ready((Ready(CrunchResult(IndexedSeq(15, 21), Seq(34, 23))), Ready(UserDeskRecs(Seq(DeskRecTimeslot("0", 45), DeskRecTimeslot("1", 30))))))))

      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
        "A1" -> Map(
            "EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 2), DeskRec(1, 1)), Seq(5, 10))),
            "eGate" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 5), DeskRec(1, 7)), Seq(15, 25))),
            "Non-EEA" -> Ready(SimulationResult(IndexedSeq(DeskRec(0, 11), DeskRec(1, 8)), Seq(30, 14)))))

      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)

      val expected = Seq(TerminalUserDeskRecsRow(0L, Seq(
        QueueDetailsRow(crunchDeskRec = 2, userDeskRec = DeskRecTimeslot("0", 2), waitTimeWithCrunchDeskRec = 20, waitTimeWithUserDeskRec = 10),
        QueueDetailsRow(crunchDeskRec = 23, userDeskRec = DeskRecTimeslot("0", 7), waitTimeWithCrunchDeskRec = 27, waitTimeWithUserDeskRec = 25),
        QueueDetailsRow(crunchDeskRec = 21, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 34, waitTimeWithUserDeskRec = 30)
      )))

      assert(expected == result)
    }

//    "Given crunch results and simulation results for 16 minutes in 1 queue, when we ask for TerminalUserDeskRecsRows " +
//      "then we should see the highest values for those minutes" - {
//      val queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[CrunchResultAndDeskRecs]]] = Map(
//        "A1" -> Map(
//          "EEA" ->
//            Ready((
//              Ready(CrunchResult(IndexedSeq(1,1,1,1,1,7,1,1,1,1,5,1,1,1,1,3), Seq(1,1,1,1,1,8,1,1,1,1,5,1,1,1,1,4))),
//              Ready(UserDeskRecs(Seq(
//                DeskRecTimeslot("0", 1), DeskRecTimeslot("1", 2),
//                DeskRecTimeslot("2", 1), DeskRecTimeslot("3", 2),
//                DeskRecTimeslot("4", 8), DeskRecTimeslot("5", 2),
//                DeskRecTimeslot("6", 1), DeskRecTimeslot("7", 2),
//                DeskRecTimeslot("8", 1), DeskRecTimeslot("9", 11),
//                DeskRecTimeslot("10", 1), DeskRecTimeslot("11", 2),
//                DeskRecTimeslot("12", 1), DeskRecTimeslot("13", 2),
//                DeskRecTimeslot("14", 1), DeskRecTimeslot("15", 5)
//              )))))))
//
//      val simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]] = Map(
//        "A1" -> Map(
//          "EEA" -> Ready(SimulationResult(IndexedSeq(
//            DeskRec(0, 2), DeskRec(1, 1),
//            DeskRec(2, 2), DeskRec(3, 1),
//            DeskRec(4, 2), DeskRec(5, 1),
//            DeskRec(6, 2), DeskRec(7, 1),
//            DeskRec(8, 2), DeskRec(9, 1),
//            DeskRec(10, 2), DeskRec(11, 1),
//            DeskRec(12, 2), DeskRec(13, 1),
//            DeskRec(14, 2), DeskRec(15, 1)
//          ), Seq(1,1,1,1,1,12,1,1,1,1,15,1,1,1,1,9)))))
//
//      val result = terminalUserDeskRecsRows(queueCrunchResults, simulationResult)
//
//      val expected = Seq(
//        TerminalUserDeskRecsRow(0L, Seq(QueueDetailsRow(crunchDeskRec = 7, userDeskRec = DeskRecTimeslot("0", 11), waitTimeWithCrunchDeskRec = 8, waitTimeWithUserDeskRec = 15))),
//        TerminalUserDeskRecsRow(15L, Seq(QueueDetailsRow(crunchDeskRec = 3, userDeskRec = DeskRecTimeslot("15", 5), waitTimeWithCrunchDeskRec = 4, waitTimeWithUserDeskRec = 9)))
//      )
//
//      assert(expected == result)
//    }
  }
}
