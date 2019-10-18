package graphs

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}

object SinkToSourceBridge {
  def apply[A](implicit materializer: Materializer): (Source[A, NotUsed], Sink[A, NotUsed]) = Source.asSubscriber[A]
    .map { x =>
      println(s"Bridging ${x.getClass}")
      x
    }
    .toMat(Sink.asPublisher[A](fanout = false))(Keep.both)
    .mapMaterializedValue { case (sub, pub) => (Source.fromPublisher(pub), Sink.fromSubscriber(sub)) }
    .run()
}
