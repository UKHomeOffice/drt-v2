package spatutorial.client.services

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.client.services.HandyStuff.QueueUserDeskRecs
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object SPACircuitTests extends TestSuite {
  def tests = TestSuite {
    'DeskRecHandler - {

      val queueName: QueueName = "eeaDesk"
      val terminalName: TerminalName = "T1"
      val model = Map(terminalName -> Map(queueName -> Ready(DeskRecTimeSlots(Seq(
        DeskRecTimeslot(1, 30),
        DeskRecTimeslot(2, 30),
        DeskRecTimeslot(3, 30),
        DeskRecTimeslot(4, 30)
      )))))

      val newTodos = Seq(
        DeskRecTimeslot(3, 15)
      )

      def build = new DeskTimesHandler(new RootModelRW(model))

      'UpdateDeskRecInModel - {
        val h = build
        val result = h.handle(UpdateDeskRecsTime(terminalName, queueName, DeskRecTimeslot(4, 25)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            val newUserDeskRecs: DeskRecTimeSlots = newValue(terminalName)(queueName).get
            assert(newUserDeskRecs.items.size == 4)
            assert(newUserDeskRecs.items(3).timeInMillis == 4)
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
          val model: Map[String, Pot[AirportInfo]] = Map("LGW" -> Empty) //todo Empty because type reasons, try and make in Pending
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

    //    'CrunchHandler - {
    //      val model: Pot[CrunchResult] = Ready(CrunchResult(IndexedSeq[Int](), Nil))
    //      def build = new CrunchHandler(new RootModelRW[Pot[CrunchResult]](model))
    //      'UpdateCrunch - {
    //        val h = build
    //        val result = h.handle(Crunch(Seq(1,2,3d)))
    //        println("handled it!")
    //        result match {
    //          case e: EffectOnly =>
    //            println(s"effect was ${e}")
    //          case ModelUpdateEffect(newValue, effects) =>
    //            assert(newValue.isPending)
    //            assert(effects.size == 1)
    //          case NoChange =>
    //          case what =>
    //            println(s"didn't handle ${what}")
    //            val badPath1 = false
    //            assert(badPath1)
    //        }
    //        val crunchResult = CrunchResult(IndexedSeq(23, 39), Seq(12, 10))
    //        val crunch: UpdateCrunch = UpdateCrunch(Ready(crunchResult))
    //        val result2 = h.handle(crunch)
    //        result2 match {
    //          case ModelUpdate(newValue) =>
    //            println(s"here we are ${newValue.isReady}")
    //            assert(newValue.isReady)
    //            assert(newValue.get == crunchResult)
    //          case _ =>
    //            val badPath2 = false
    //            assert(badPath2)
    //        }
    //      }
    //    }

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
              (Map("eeaGate" ->
                (Seq(WL(0, 1.2)), Seq(Pax(0, 1.0))))))))

        val expected = RootModel().copy(
          workload = Ready(Workloads(Map("T1" -> Map("eeaGate" ->(Seq(WL(0, 1.2)), Seq(Pax(0, 1.0))))))))

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
          UpdateCrunchResult("A1", "EEA", CrunchResultWithTimeAndInterval(0, 60000, IndexedSeq(33), Seq(29))))

        val expected = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready(
              (
                Ready(
                  CrunchResult(
                    Vector(33), List(29)
                  )
                ),
                Ready(
                  DeskRecTimeSlots(
                    List(
                      DeskRecTimeslot(0, 33)
                    )
                  )
                )
                )
            )
          )),
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(
            DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))
          ))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model that already has a crunch result for a queue," +
        " when we apply the results for a new queue then we should see both existing queue and new queue" - {
        val model = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))))
          )),
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(
            DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))
          ))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "eGates", CrunchResultWithTimeAndInterval(0, 60000, IndexedSeq(33), Seq(29))))

        val expected = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33)))))),
            "eGates" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))))
          )),
          userDeskRec = Map("A1" -> Map(
            "EEA" -> Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33)))),
            "eGates" -> Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model that already has a crunch result for 2 queues," +
        " when we apply an update for one queue then we should see both existing queue and updated queue" - {
        val model = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33)))))),
            "eGates" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))))
          )),
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(
            DeskRecTimeSlots(List(DeskRecTimeslot(0, 33))))
          ))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "eGates", CrunchResultWithTimeAndInterval(0, 60000, IndexedSeq(22), Seq(23))))

        val expected = RootModel().copy(
          queueCrunchResults = Map("A1" -> Map(
            "EEA" -> Ready((Ready(CrunchResult(Vector(33), List(29))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33)))))),
            "eGates" -> Ready((Ready(CrunchResult(Vector(22), List(23))), Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 22))))))
          )),
          userDeskRec = Map("A1" -> Map(
            "EEA" -> Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 33)))),
            "eGates" -> Ready(DeskRecTimeSlots(List(DeskRecTimeslot(0, 22))))))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
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
      "Given a model with user desk recs, when we update a user desk rec, then that value should be updated in the model" - {
        val model = RootModel().copy(
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 5))))))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(model, ChangeDeskUsage("A1", "EEA", "6", 1))

        val expected = RootModel().copy(
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 6))))))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model with two queues of desk recs, when we update one of them, then we should see desk recs for both queues with the updated values" - {
        val model = RootModel().copy(
          userDeskRec = Map("A1" -> Map(
            "EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 5)))),
            "eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 5))))
          ))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(model, ChangeDeskUsage("A1", "EEA", "6", 1))

        val expected = RootModel().copy(
          userDeskRec = Map("A1" -> Map(
            "EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 6)))),
            "eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(1, 5))))
          ))
        )
        res match {
          case Some(ModelUpdate(newValue)) =>
            assert(newValue == expected)
          case default =>
            println(default)
            assert(false)
        }
      }
      "Given a model with user desk recs, when we update UserDeskRecsTime then we should see updated wait times" - {
        val model = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44))))),
          userDeskRec = Map("A1" -> Map("eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 6))))))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(model, UpdateDeskRecsTime("A1", "eGates", DeskRecTimeslot(0, 5)))

        val expected = RootModel().copy(
          simulationResult = Map("A1" -> Map("eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44))))),
          userDeskRec = Map("A1" -> Map("eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 5))))))
        )
        res match {
          case Some(ModelUpdateEffect(newValue, effect)) =>
            assert(newValue == expected)
          case default =>
            println(s"Failure: $default")
            assert(false)
        }
      }
      "Given a model with desk recs for two queues, when we update one, we should see both queues desk recs" - {
        val model = RootModel().copy(
          simulationResult = Map("A1" -> Map(
            "eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44))),
            "EEA" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44)))
          )),
          userDeskRec = Map("A1" -> Map(
            "eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 6)))),
            "EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 6))))
          ))
        )
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(model, UpdateDeskRecsTime("A1", "eGates", DeskRecTimeslot(0, 5)))

        val expected = RootModel().copy(
          simulationResult = Map("A1" -> Map(
            "eGates" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44))),
            "EEA" -> Ready(SimulationResult(Vector(DeskRec(200, 30)), List(44)))
          )),
          userDeskRec = Map("A1" -> Map(
            "eGates" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 5)))),
            "EEA" -> Ready(DeskRecTimeSlots(Seq(DeskRecTimeslot(0, 6))))
          ))
        )
        res match {
          case Some(ModelUpdateEffect(newValue, effect)) =>
            assert(newValue == expected)
          case default =>
            println(s"Failure: $default")
            assert(false)
        }
      }
      import RootModel._
      "Map merge" - {
        val m1 = Map("T1" -> Map("EEA" -> Seq(1)))
        val m2 = Map("T1" -> Map("eGates" -> Seq(2)))

        val cleaned = mergeTerminalQueues(m1, m2)

        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(2)))

        assert(expected == cleaned)
      }
      "Map merge 2" - {
        val m1 = Map("T1" -> Map("eGates" -> Seq(3)))
        val m2 = Map("T1" -> Map("EEA" -> Seq(1)))

        val cleaned = mergeTerminalQueues(m1, m2)


        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(3)))
        assert(expected == cleaned)

      }
      "Map merge 3" - {
        val m1 = Map("T1" -> Map("eGates" -> Seq(3)))
        val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        val cleaned = mergeTerminalQueues(m1, m2)

        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        assert(expected == cleaned)
      }
      "Map merge 4" - {
        val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)))
        val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        val cleaned = mergeTerminalQueues(m1, m2)

        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        assert(expected == cleaned)
      }
      "Map merge 5" - {
        val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))
        val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        val cleaned = mergeTerminalQueues(m1, m2)

        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))

        assert(expected == cleaned)
      }
      "Map merge 6" - {
        val m1 = Map("T1" -> Map("eGates" -> Seq(3), "EEA" -> Seq(9)), "T2" -> Map("eGates" -> Seq(0), "EEA" -> Seq(8)))
        val m2 = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)))

        val cleaned = mergeTerminalQueues(m1, m2)

        val expected = Map("T1" -> Map("EEA" -> Seq(1), "eGates" -> Seq(1)), "T2" -> Map("eGates" -> Seq(1), "EEA" -> Seq(1)))

        assert(expected == cleaned)
      }
    }
  }

}
