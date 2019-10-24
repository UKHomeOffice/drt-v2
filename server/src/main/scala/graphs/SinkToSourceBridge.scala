package graphs

import akka.NotUsed
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink, Source}

object SinkToSourceBridge {
  def apply[A](implicit materializer: Materializer): (Source[A, NotUsed], UniqueKillSwitch, Sink[A, NotUsed]) = Source.asSubscriber[A]
    .viaMat(KillSwitches.single)(Keep.both)
    .toMat(Sink.asPublisher[A](fanout = false))(Keep.both)
    .mapMaterializedValue { case ((sub, ks), pub) => (Source.fromPublisher(pub), ks, Sink.fromSubscriber(sub)) }
    .run()
}
