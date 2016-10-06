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
      val model = Map(terminalName -> Map(queueName -> Ready(UserDeskRecs(Seq(
        DeskRecTimeslot("1", 30),
        DeskRecTimeslot("2", 30),
        DeskRecTimeslot("3", 30),
        DeskRecTimeslot("4", 30)
      )))))

      val newTodos = Seq(
        DeskRecTimeslot("3", 15)
      )

      def build = new DeskTimesHandler(new RootModelRW(model))

      'UpdateAllTodos - {
        val h = build
        val result = h.handle(UpdateQueueUserDeskRecs(terminalName, queueName, newTodos))
        val expected = ModelUpdate(Map(terminalName -> Map(queueName -> Ready(UserDeskRecs(newTodos)))))
        assert(result == expected)
      }

      'UpdateDeskRecInModel - {
        val h = build
        val result = h.handle(UpdateDeskRecsTime(terminalName, queueName, DeskRecTimeslot("4", 25)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            val newUserDeskRecs: UserDeskRecs = newValue(terminalName)(queueName).get
            assert(newUserDeskRecs.items.size == 4)
            assert(newUserDeskRecs.items(3).id == "4")
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
          case Some(ModelUpdateEffect(newValue, effects)) =>
            assert(newValue == expected)
          case default => assert(false)
        }
      }
      "Update crunch results" - {
        val model = RootModel()
        val handler: SPACircuit.HandlerFunction = SPACircuit.actionHandler
        val res = handler.apply(
          model,
          UpdateCrunchResult("A1", "EEA", CrunchResult(IndexedSeq(33), Seq(29))))

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
                  UserDeskRecs(
                    List(
                      DeskRecTimeslot("0", 33)
                    )
                  )
                )
                )
            )
          )),
          userDeskRec = Map("A1" -> Map("EEA" -> Ready(
            UserDeskRecs(List(DeskRecTimeslot("0", 33))))
          ))
        )
        println(expected)
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
}
