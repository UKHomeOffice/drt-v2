package services

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Attributes}
import akka.stream.scaladsl.{Sink, Source}
import org.specs2.mutable.Specification

import scala.collection.immutable.SortedSet
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

class StreamingWorkloadSpec extends Specification {
  val slowCalc: Int => Future[Unit] = (d: Int) => Future {
    Thread.sleep(1000)
    println(s"Calced $d")
  }

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  "Given a slow mock workload calculator" >> {
    "When I produce more days to calculate" >> {
      "What do I see arriving at the calculator?" >> {
        skipped("experimental")
        val parallelism = 2
        var days = List(List(1, 2, 3, 4, 5, 6, 7), List(10), List(11), List(1))
        def nextDays: List[Int] = days match {
          case Nil => List()
          case head :: tail =>
            days = tail
            println(s"Adding $head\n")
            head
        }
        val eventualCalcs = Source.tick(0 milliseconds, 750 milliseconds, NotUsed).withAttributes(Attributes.inputBuffer(1, 1))
          .map(_ => nextDays)
          .statefulMapConcat { () =>
            var queue: SortedSet[Int] = SortedSet()
            incoming => {
              queue = queue ++ incoming
              queue match {
                case q if q.nonEmpty =>
                  val head = q.take(parallelism)
                  queue = queue.drop(parallelism)
                  println(s"queue now: $queue")
                  List(head).flatten
                case _ =>
                  println(s"queue empty")
                  List()
              }
            }
          }
          .mapAsync(parallelism) { d =>
            println(s"Calc taking $d")
            slowCalc(d)
          }
          .runWith(Sink.seq)

        Await.result(eventualCalcs, 20 seconds)

        success
      }
    }
  }
}
