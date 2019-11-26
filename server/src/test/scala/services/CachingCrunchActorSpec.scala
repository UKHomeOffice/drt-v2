package services

import actors.{CachableActorQuery, CachingCrunchReadActor}
import akka.actor._
import akka.pattern.AskableActorRef
import akka.stream._
import akka.testkit.TestKit
import akka.util.Timeout
import controllers.GetTerminalCrunch
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.{T1, Terminal}
import org.specs2.control.LanguageFeatures
import org.specs2.mutable.SpecificationLike

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Await
import scala.concurrent.duration._

case object WasCalled

class TestActorProbe(pointInTime: SDateLike, queues: Map[Terminal, Seq[Queue]], incrementer: () => Unit) extends Actor {

  def receive: Receive = {
    case _: GetTerminalCrunch =>
      incrementer()
      sender() ! s"Terminal Crunch Results for ${pointInTime.toISOString()}"
  }
}

class CachingCrunchActorSpec extends TestKit(ActorSystem("CacheTests")) with SpecificationLike with LanguageFeatures {
  isolated
  sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(1 seconds)

  val cacheActorRef: AskableActorRef = system.actorOf(Props(classOf[CachingCrunchReadActor]), name = "crunch-cache-actor")
  "Should pass a message onto the crunch actor and return the response" >> {

    def inc() = {}
    val query = CachableActorQuery(Props(classOf[TestActorProbe], SDate("2017-06-01T20:00:00Z"), Map(), inc _), GetTerminalCrunch(T1))
    val resultFuture = cacheActorRef.ask(query)

    val result = Await.result(resultFuture, 1 second)
    val expected = "Terminal Crunch Results for 2017-06-01T20:00:00Z"

    result === expected
  }

  "When the same query is sent twice" >> {
    var called = 0
    def inc() = {
      called +=1
    }

    val query = CachableActorQuery(Props(classOf[TestActorProbe], SDate("2017-06-01T20:00:00Z"), Map(), inc _), GetTerminalCrunch(T1))

    val resF2 = cacheActorRef.ask(query)

    val res1 = Await.result(resF2, 1 second)
    val res2 = Await.result(resF2, 1 second)

    "The first result should equal the second" >> {
      res1 === res2
    }
    "The underlying actor should be called only once" >> {
      called === 1
    }
  }

  "When two different queries are sent" >> {
    var called = 0
    def inc() = {
      called +=1
    }

    val query1 = CachableActorQuery(Props(classOf[TestActorProbe], SDate("2017-06-01T20:00:00Z"), Map(), inc _), GetTerminalCrunch(T1))
    val resF1 = cacheActorRef.ask(query1)
    val res1 = Await.result(resF1, 1 second)

    val query2 = CachableActorQuery(Props(classOf[TestActorProbe], SDate("2017-06-01T20:05:00Z"), Map(), inc _), GetTerminalCrunch(T1))
    val resF2 = cacheActorRef.ask(query2)
    val res2 = Await.result(resF2, 1 second)

    "The first result should not equal the second" >> {
      res1 !== res2
    }
    "The underlying actor should be called twice" >> {
      called === 2
    }
  }
}
