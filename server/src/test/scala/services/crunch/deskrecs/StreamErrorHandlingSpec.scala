package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.ActorAttributes
import akka.stream.scaladsl.{Sink, Source}
import services.StreamSupervision
import services.crunch.CrunchTestLike

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object SomeAwesomeObject {
  def source(implicit ec: ExecutionContext): Source[Int, NotUsed] = Source(List(1, 2, 3))
    .mapAsync(1) {
      case number if number == 2 => Future(throw new Exception("boom"))
      case number => Future(number)
    }
    .withAttributes(ActorAttributes.supervisionStrategy(StreamSupervision.resumeWithLog(getClass.getName)))
}

class StreamErrorHandlingSpec extends CrunchTestLike {
  "Given a stream of ints 1, 2, 3" >> {
    "When I mapAsync over it and 2 causes an exception to be thrown in the Future" >> {
      "Then I should see the stream recover from the exception and skip 2, leaving 1 & 3" >> {
        val stream = SomeAwesomeObject.source.runWith(Sink.seq)
        val nos = Await.result(stream, 1.second)

        nos === List(1, 3)
      }
    }
  }
}
