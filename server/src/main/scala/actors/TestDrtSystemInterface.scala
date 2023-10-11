package actors

import akka.actor.{ActorRef, typed}
import drt.server.feeds._
import uk.gov.homeoffice.drt.time.{MilliDate => _}

import scala.language.postfixOps

trait TestDrtSystemInterface extends DrtSystemInterface {
  val testManifestsActor: ActorRef
  val testArrivalActor: ActorRef
  val testFeed: Feed[typed.ActorRef[Feed.FeedTick]]
  val restartActor: ActorRef
}
