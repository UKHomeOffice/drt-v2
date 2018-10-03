package actors


import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.{MilliDate, StaffMovement}
import org.specs2.matcher.Scope
import org.specs2.mutable.{After, Specification, SpecificationLike}
import services.SDate
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.language.reflectiveCalls
import scala.util.Success

object PersistenceTestHelper {
  val dbLocation = "target/test"
}

abstract class AkkaTestkitSpecs2SupportForPersistence2(val dbLocation: String) extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
  "akka.persistence.journal.leveldb.dir" -> dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"$dbLocation/snapshot"
))))
  //  with After
  with ImplicitSender {

  //  def after: Unit = {
  //    shutDownActorSystem
  //    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
  //    PersistenceCleanup.deleteJournal(dbLocation)
  //  }

  def shutDownActorSystem: Future[Terminated] = {
    //TODO figure out how to wait for the actor to finish saving rather than this nasty timer.
    Thread.sleep(200)
    import scala.language.postfixOps
    Await.ready(system.terminate(), 2 second)
    Await.ready(system.whenTerminated, 2 second)
  }
}

class StaffMovementsActorSpec extends AkkaTestkitSpecs2SupportForPersistence2(PersistenceTestHelper.dbLocation) with SpecificationLike {
  sequential
  isolated

  private def staffMovementsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "staffMovementsActor")
    actor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def getActor: ActorRef = staffMovementsActor(system)

  def getState(actor: ActorRef) = {
    Await.result(actor ? GetState, 1 second)
  }

  def getStateAndShutdown(actor: ActorRef): Any = {
    val s = getState(actor)
    shutDownActorSystem
    s
  }

  "StaffMovementsActor" should {
    "return the movements that were added after a shutdown" >> {

      PersistenceCleanup.deleteJournal(dbLocation)

      val movementUuid1: UUID = UUID.randomUUID()
      val staffMovements = StaffMovements(Seq(StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))))

      val actor = getActor

      actor ! AddStaffMovements(staffMovements.movements)

      val result = getStateAndShutdown(actor)

      result mustEqual staffMovements
    }

    "return no movements if the movements were removed after a restart" in {

      PersistenceCleanup.deleteJournal(dbLocation)

      val movementUuid1: UUID = UUID.randomUUID()
      val movementUuid2: UUID = UUID.randomUUID()

      val movement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement("T1", "coffee start", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), -1, movementUuid2, createdBy = Some("robin"))
      val staffMovements = StaffMovements(Seq(movement1, movement2))

      val actor = getActor

      actor ! AddStaffMovements(staffMovements.movements)

      Thread.sleep(100)

      actor ! RemoveStaffMovements(movementUuid1)
      actor ! PoisonPill

      Thread.sleep(100)

      val newActor = getActor

      val result = Await.result(newActor ? GetState, 1 second)
      val expected = StaffMovements(Seq(movement2))

      result mustEqual expected
    }
  }

}
