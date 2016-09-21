package spatutorial.client.services

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._
import utest._

object SPACircuitTests extends TestSuite {
  def tests = TestSuite {
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
  }
}
