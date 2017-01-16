package spatutorial.client.services

import diode.ActionResult._
import diode.{ActionResult, ModelR, RootModelRW}
import diode.data._
import spatutorial.client.UserDeskRecFixtures._
import spatutorial.client.services.HandyStuff.PotCrunchResult
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}


object SPACircuitTests extends TestSuite {
  def tests = TestSuite {


    'DeskRecHandler - {

      val queueName: QueueName = "eeaDesk"
      val terminalName: TerminalName = "T1"
      val model = Map(terminalName -> makeUserDeskRecs(queueName, List(30, 30, 30, 30)))

      val newTodos = Seq(
        DeskRecTimeslot(3, 15)
      )

      def build = new DeskTimesHandler(new RootModelRW(model))

      'UpdateDeskRecInModel - {
        val h = build
        val result = h.handle(UpdateDeskRecsTime(terminalName, queueName, DeskRecTimeslot(3 * 15 * 60000L, 25)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            val newUserDeskRecs: DeskRecTimeSlots = newValue(terminalName)(queueName).get
            assert(newUserDeskRecs.items.size == 4)
            assert(newUserDeskRecs.items(3).timeInMillis == 3 * 15 * 60000L)
            assert(newUserDeskRecs.items(3).deskRec == 25)
            assert(effects.size == 1)
          case message =>
            assert(false)
        }
      }
      val timeProvider = () => 93L

      'AirportCountryHandler - {
        "Given no inital state " - {
          val model: Map[String, Pot[AirportInfo]] = Map.empty

          def build = new AirportCountryHandler(timeProvider, new RootModelRW(model))

          val h = build
          "when we request  airportinfo mappings we see a model change to reflect the pending state and the effect" - {
            val result = h.handle(GetAirportInfos(Set("BHX", "EDI")))
            result match {
              case ModelUpdateEffect(newValue, effect) =>
                assert(newValue == Map("BHX" -> Pending(93L), "EDI" -> Pending(93L))) // using Empty as Pending seems to have covariance issues, or i don't understand it
                println(effect.toString)
                assert(effect.size == 1) //todo figure out how to mock/assert the effect
            }
          }
          "when we update a single port code we see the model change " - {
            val info = AirportInfo("Gatwick", "Gatwick", "United Kingdom", "LGW")
            val someInfo: Some[AirportInfo] = Some(info)
            val result = h.handle(UpdateAirportInfo("LGW", someInfo))
            result match {
              case ModelUpdate(newValue) =>
                assert(newValue == Map(("LGW" -> Ready(info))))
              case message =>
                println(s"Message was ${message}")
                assert(false)
            }
          }

          "when we update a set of ports code we see the model change " - {
            val lgwInfo = AirportInfo("Gatwick", "Gatwick", "United Kingdom", "LGW")
            val lhrInfo = AirportInfo("Heathrow", "Heathrow", "United Kingdom", "LHR")

            val infos = Map("LGW" -> lgwInfo, "LHR" -> lhrInfo)

            val result = h.handle(UpdateAirportInfos(infos))
            result match {
              case ModelUpdate(newValue) =>
                assert(newValue == Map("LGW" -> Ready(lgwInfo), "LHR" -> Ready(lhrInfo)))
              case message =>
                println(s"Message was ${message}")
                assert(false)
            }
          }
          "when we update a single LHR port code we see the model change " - {
            val info = AirportInfo("LHR", "London", "United Kingdom", "LHR")
            val someInfo: Some[AirportInfo] = Some(info)
            val result = h.handle(UpdateAirportInfo("LHR", someInfo))
            result match {
              case ModelUpdate(newValue) =>
                assert(newValue == Map(("LHR" -> Ready(info))))
              case message =>
                println(s"Message was ${message}")
                assert(false)
            }
          }

        }
        "Given a pending request" - {
          val model: Map[String, Pot[AirportInfo]] = Map("LGW" -> Empty)

          //todo Empty because type reasons, try and make in Pending
          def build = new AirportCountryHandler(timeProvider, new RootModelRW(model))

          val h = build
          "when we request a mapping for the existing request we see noChange" - {
            val result = h.handle(GetAirportInfo("LGW"))
            result match {
              case NoChange =>
                assert(true)
              case m =>
                println(s"should not have got $m")
                assert(false)
            }
          }
        }
      }
    }

    'CrunchHandler - {
      val circuit = new DrtCircuit {}

      'UpdateCrunch - {
        "on updateCrunch the queueCrunchResults are set - we separate the desk recs and estimated " - {
          circuit.dispatch(UpdateCrunchResult("T1", "eeaDesk", CrunchResult(0, 60000, IndexedSeq(33), Seq(29))))
          val newModel: ModelR[RootModel, Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]] = circuit.zoom(_.queueCrunchResults)
          val actualQueueCrunchResults = newModel.value
          val expectedQueueCrunchResults = Map("T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(0, 60000, Vector(33), List(29))))))
          assert(actualQueueCrunchResults == expectedQueueCrunchResults)
        }
        "on updateCrunch the UserDeskRecs (sp?) should be calculated according to Staff Availability" - {
          circuit.dispatch(UpdateCrunchResult("T1", "eeaDesk", CrunchResult(0, 60000, IndexedSeq(33), Seq(29))))
          val newModel: ModelR[RootModel, Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]] = circuit.zoom(_.queueCrunchResults)
          val actualQueueCrunchResults = newModel.value
          val expectedQueueCrunchResults = Map("T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(0, 60000, Vector(33), List(29))))))
          assert(actualQueueCrunchResults == expectedQueueCrunchResults)
        }
      }
    }

    'FlightsHandler - {
      "given no flights, when we start, then we request flights from the api" - {
        val model: Pot[Flights] = Empty

        def build = new FlightsHandler(new RootModelRW[Pot[Flights]](model))
      }
    }

    'SPACircuitHandler - {
      "Model workloads update" - {
        val model = RootModel()
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateWorkloads(
            Map("T1" ->
              Map("eeaGate" -> (Seq(WL(0, 1.2)), Seq(Pax(0, 1.0)))))))

        val expected = RootModel().copy(
          workload = Ready(Workloads(Map("T1" -> Map("eeaGate" -> (Seq(WL(0, 1.2)), Seq(Pax(0, 1.0))))))))

        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println("Was " + default.toString())
            assert(false)
        }
      }
      "Update crunch results" - {
        val model = RootModel()
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "EEA", CrunchResult(0, 60000, IndexedSeq(33), Seq(29))))

        val expectedQueueCrunchResults = Map("A1" -> Map(
          "EEA" -> Ready(Ready(CrunchResult(0, 60000, Vector(33), List(29))))))

        assertQueueCrunchResult(res, expectedQueueCrunchResults)
      }
      "Given a model that already has a crunch result for a queue," +
        " when we apply the results for a new queue then we should see both existing queue and new queue" - {
        val model = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map("EEA" -> Ready(Ready(CrunchResult(0, 60000, Vector(33), List(29))))))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "eGates", CrunchResult(0, 60000, IndexedSeq(33), Seq(29))))

        val expectedQueueCrunchResults = Map("A1" -> Map(
          "EEA" -> Ready((Ready(CrunchResult(0, 60000, Vector(33), List(29))))),
          "eGates" -> Ready((Ready(CrunchResult(0, 60000, Vector(33), List(29)))))
        ))

        assertQueueCrunchResult(res, expectedQueueCrunchResults)
      }
      "Given a model that already has a crunch result for 2 queues," +
        " when we apply an update for one queue then we should see both existing queue and updated queue in the queueCrunchResults" - {
        val model = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready((Ready(CrunchResult(0, 60000, Vector(33), List(29))))),
            "eGates" -> Ready((Ready(CrunchResult(0, 60000, Vector(33), List(29)))))
          ))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "eGates", CrunchResult(0, 60000, IndexedSeq(22), Seq(23))))

        val expectedQueueCrunchResults = Map("A1" -> Map(
          "EEA" -> Ready((Ready(CrunchResult(0, 60000, Vector(33), List(29))))),
          "eGates" -> Ready((Ready(CrunchResult(0, 60000, Vector(22), List(23)))))
        ))

        assertQueueCrunchResult(res, expectedQueueCrunchResults)
      }
      "Given an empty model when we run a simulation, then the result of the simulation should be in the model" - {
        val model = RootModel()
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateSimulationResult("A1", "eGates", SimulationResult(IndexedSeq(DeskRec(100, 10)), Seq(23))))

        val expected = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(100, 10)), List(23)))))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model with an existing simulation, when we run a simulation, then the new results should be in the model" - {
        val model = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44)))))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateSimulationResult("A1", "eGates", SimulationResult(IndexedSeq(DeskRec(100, 10)), Seq(23))))

        val expected = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(100, 10)), List(23)))))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model with an existing simulation desk, when we run a simulation on a different desk," +
        " then both desks should be in the model" - {
        val model = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44)))))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateSimulationResult("A1", "EEA", SimulationResult(IndexedSeq(DeskRec(100, 10)), Seq(23))))

        val expected = RootModel().copy(
          simulationResult = Map("A1" -> Map(
            "eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44))),
            "EEA" -> Ready(SimulationResult(Vector(DeskRec(100, 10)), List(23))))
          ))
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
    }
  }

  private def assertQueueCrunchResult(res: Option[ActionResult[RootModel]], expectedQueueCrunchResults: Map[QueueName, Map[QueueName, Ready[(Ready[CrunchResult])]]]) = {
    res match {
      case Some(ModelUpdate(newValue)) =>
        val actualQueueCrunchResults = newValue.queueCrunchResults
        assert(actualQueueCrunchResults == expectedQueueCrunchResults)
      case default =>
        println(default)
        assert(false)
    }
  }
}
