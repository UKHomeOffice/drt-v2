package services

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.specs2.mutable.Specification

import scala.collection.immutable.SortedSet
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

class StreamingWorkloadSpec extends Specification {
  val slowCalc: Int => Future[Unit] = (d: Int) => Future {
    Thread.sleep(1000)
  }

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  "Given a slow mock workload calculator" >> {
    "When I produce more days to calculate" >> {
      "What do I see arriving at the calculator?" >> {
        skipped("experimental")
        var days = List(List(1, 2), List(3), List(4), List(5), List(6), List(1), List(7), List(8))
        def nextDays: List[Int] = days match {
          case Nil => List()
          case head :: tail =>
            days = tail
            println(s"Adding $head\n")
            head
        }
        val eventualCalcs = Source.tick(0 milliseconds, 750 milliseconds, NotUsed)
          .map(_ => nextDays)
          .conflateWithSeed(SortedSet[Int]() ++ _) {
            case (daysQueue, newDays) =>
              val newQueue = daysQueue ++ newDays
              println(s"Got $newDays. queue now $newQueue")
              newQueue
          }
          .mapConcat(identity)
          .mapAsync(1) { d =>
            slowCalc(d)
          }
          .runWith(Sink.seq)

        Await.result(eventualCalcs, 10 seconds)

        success
      }
    }
  }
}
