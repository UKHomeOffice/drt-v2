package drt.chroma

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.testkit.TestSubscriber.Probe
import akka.testkit.TestKit
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike

import scala.reflect.macros.blackbox

abstract class AkkaStreamTestKitSpecificationLike extends TestKit(ActorSystem()) with SpecificationLike {
  implicit val materializer = ActorMaterializer()
  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

//  implicit def probe2MatchResult[R](r: R): MatchResult[Any] = ok
}

object MatcherHelper {
  def matcherhelper(c: blackbox.Context)(r: c.Tree) = {
    c.error(c.enclosingPosition, "test should finish with a Matcher or a Probe[T]")
    c.abort(c.enclosingPosition, "blah")
  }
}
