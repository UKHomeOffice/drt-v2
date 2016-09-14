package spatutorial.client.services

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._
import utest._

object SPACircuitTests extends TestSuite {
  def tests = TestSuite {
    'TodoHandler - {
      val model = Ready(Todos(Seq(
        TodoItem("1", 0, "Test1", TodoLow, completed = false),
        TodoItem("2", 0, "Test2", TodoLow, completed = false),
        TodoItem("3", 0, "Test3", TodoHigh, completed = true)
      )))

      val newTodos = Seq(
        TodoItem("3", 0, "Test3", TodoHigh, completed = true)
      )

      def build = new TodoHandler(new RootModelRW(model))

      'RefreshTodos - {
        val h = build
        val result = h.handle(RefreshTodos)
        result match {
          case EffectOnly(effects) =>
            assert(effects.size == 1)
          case _ =>
            assert(false)
        }
      }

      'UpdateAllTodos - {
        val h = build
        val result = h.handle(UpdateAllTodos(newTodos))
        assert(result == ModelUpdate(Ready(Todos(newTodos))))
      }

      'UpdateTodoAdd - {
        val h = build
        val result = h.handle(UpdateTodo(TodoItem("4", 0, "Test4", TodoNormal, completed = false)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            assert(newValue.get.items.size == 4)
            assert(newValue.get.items(3).id == "4")
            assert(effects.size == 1)
          case _ =>
            assert(false)
        }
      }

      'UpdateTodo - {
        val h = build
        val result = h.handle(UpdateTodo(TodoItem("1", 0, "Test111", TodoNormal, completed = false)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            assert(newValue.get.items.size == 3)
            assert(newValue.get.items.head.content == "Test111")
            assert(effects.size == 1)
          case _ =>
            assert(false)
        }
      }

      'DeleteTodo - {
        val h = build
        val result = h.handle(DeleteTodo(model.get.items.head))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            assert(newValue.get.items.size == 2)
            assert(newValue.get.items.head.content == "Test2")
            assert(effects.size == 1)
          case _ =>
            assert(false)
        }
      }
    }

    'MotdHandler - {
      val model: Pot[String] = Ready("Message of the Day!")
      def build = new MotdHandler(new RootModelRW(model))

      'UpdateMotd - {
        val h = build
        var result = h.handle(UpdateMotd())
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            assert(newValue.isPending)
            assert(effects.size == 1)
          case _ =>
            assert(false)
        }
        result = h.handle(UpdateMotd(Ready("New message")))
        result match {
          case ModelUpdate(newValue) =>
            assert(newValue.isReady)
            assert(newValue.get == "New message")
          case _ =>
            assert(false)
        }
      }
    }
    'CrunchHandler - {
      val model: Pot[CrunchResult] = Ready(CrunchResult(IndexedSeq[Int](), Nil))
      def build = new CrunchHandler(new RootModelRW[Pot[CrunchResult]](model))
      'UpdateCrunch - {
        val h = build
        val result = h.handle(UpdateCrunch())
        println("handled it!")
        result match {
          case e: EffectOnly =>
            println(s"effect was ${e}")
          case ModelUpdateEffect(newValue, effects) =>
            assert(newValue.isPending)
            assert(effects.size == 1)
          case NoChange =>
          case what =>
            println(s"didn't handle ${what}")
            val badPath1 = false
            assert(badPath1)
        }
        val crunchResult = CrunchResult(IndexedSeq(23, 39), Seq(12, 10))
        val crunch: UpdateCrunch = UpdateCrunch(Ready(crunchResult))
        val result2 = h.handle(crunch)
        result2 match {
          case ModelUpdate(newValue) =>
            println(s"here we are ${newValue.isReady}")
            assert(newValue.isReady)
            assert(newValue.get == crunchResult)
          case _ =>
            val badPath2 = false
            assert(badPath2)
        }
      }
    }

    'FlightsHandler - {
      "given no flights, when we start, then we request flights from the api" - {
        val model: Pot[Flights] = Empty
        def build = new FlightsHandler(new RootModelRW[Pot[Flights]](model))
      }
    }
  }
}
