package actors


import java.util.UUID

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import drt.shared.{MilliDate, StaffMovement}
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterEach
import services.SDate

import scala.collection.JavaConversions._
import scala.language.reflectiveCalls


object PersistenceHelper {
  val dbLocation = "target/test"
}

class StaffMovementsActorSpec extends TestKit(ActorSystem("StaffMovementsActorSpec", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.journal.leveldb.dir" -> PersistenceHelper.dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"${PersistenceHelper.dbLocation}/snapshot"
))))
  with SpecificationLike
  with AfterEach
  with ImplicitSender {
  sequential
  isolated

  override def after: Unit = {
    TestKit.shutdownActorSystem(system)
    PersistenceCleanup.deleteJournal(PersistenceHelper.dbLocation)
  }

  "StaffMovementsActor" should {
    "return the movements that were added after a shutdown" in {

      val movementUuid1: UUID = UUID.randomUUID()
      val staffMovements = StaffMovements(Seq(StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))))

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "movementsActor")

      actor ! AddStaffMovements(staffMovements.movements)
      expectMsg(AddStaffMovementsAck(staffMovements.movements))
      actor ! PoisonPill

      Thread.sleep(100)

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "movementsActor")

      newActor ! GetState

      expectMsg(staffMovements)

      true
    }

    "return no movements if the movements were removed after a restart" in {

      val movementUuid1: UUID = UUID.randomUUID()
      val movementUuid2: UUID = UUID.randomUUID()

      val movement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement("T1", "coffee start", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), -1, movementUuid2, createdBy = Some("robin"))
      val staffMovements = StaffMovements(Seq(movement1, movement2))

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "movementsActor1")

      actor ! AddStaffMovements(staffMovements.movements)
      expectMsg(AddStaffMovementsAck(staffMovements.movements))

      actor ! RemoveStaffMovements(movementUuid1)
      expectMsg(RemoveStaffMovementsAck(movementUuid1))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "movementsActor2")

      newActor ! GetState
      val expected = StaffMovements(Seq(movement2))

      expectMsg(expected)

      true
    }
  }

}
