package actors


import java.util.UUID

import actors.pointInTime.StaffMovementsReadActor
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import drt.shared.{MilliDate, SDateLike, StaffMovement}
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterEach
import services.SDate

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.language.reflectiveCalls


object PersistenceHelper {
  val dbLocation = "target/test"
}

class StaffMovementsActorSpec extends TestKit(ActorSystem("StaffMovementsActorSpec", ConfigFactory.parseMap(Map(
  "akka.log-dead-letters" -> 0,
  "akka.actor.warn-about-java-serializer-usage" -> false,
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
    "remember a movement added before a shutdown" in {
      val movementUuid1: UUID = UUID.randomUUID()
      val staffMovements = StaffMovements(Seq(StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(staffMovements.movements)
      expectMsg(AddStaffMovementsAck(staffMovements.movements))
      actor ! PoisonPill

      Thread.sleep(100)

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor2")

      newActor ! GetState

      expectMsg(staffMovements)

      true
    }

    "correctly remove a movement after a restart" in {
      val movementUuid1: UUID = UUID.randomUUID()
      val movementUuid2: UUID = UUID.randomUUID()

      val movement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement("T1", "coffee start", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), -1, movementUuid2, createdBy = Some("robin"))
      val staffMovements = StaffMovements(Seq(movement1, movement2))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(staffMovements.movements)
      expectMsg(AddStaffMovementsAck(staffMovements.movements))

      actor ! RemoveStaffMovements(movementUuid1)
      expectMsg(RemoveStaffMovementsAck(movementUuid1))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(movement2)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "remember multiple added movements and correctly forget removed movements after a restart" in {
      val movementUuid1: UUID = UUID.randomUUID()
      val movementUuid2: UUID = UUID.randomUUID()
      val movementUuid3: UUID = UUID.randomUUID()
      val movementUuid4: UUID = UUID.randomUUID()

      val movement1 = StaffMovement("T1", "lunch start", MilliDate(SDate("2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement("T1", "coffee start", MilliDate(SDate("2017-01-01T01:15").millisSinceEpoch), -1, movementUuid2, createdBy = Some("robin"))
      val movement3 = StaffMovement("T1", "supper start", MilliDate(SDate("2017-01-01T21:30").millisSinceEpoch), -1, movementUuid3, createdBy = Some("bruce"))
      val movement4 = StaffMovement("T1", "supper start", MilliDate(SDate("2017-01-01T21:40").millisSinceEpoch), -1, movementUuid4, createdBy = Some("bruce"))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(movement1, movement2))
      expectMsg(AddStaffMovementsAck(Seq(movement1, movement2)))

      actor ! RemoveStaffMovements(movementUuid1)
      expectMsg(RemoveStaffMovementsAck(movementUuid1))

      actor ! AddStaffMovements(Seq(movement3, movement4))
      expectMsg(AddStaffMovementsAck(Seq(movement3, movement4)))

      actor ! RemoveStaffMovements(movementUuid4)
      expectMsg(RemoveStaffMovementsAck(movementUuid4))

      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(movement2, movement3)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge movements created more than the specified expiry period ago" in {
      val movementUuid1: UUID = UUID.randomUUID()
      val movementUuid2: UUID = UUID.randomUUID()
      val movementUuid3: UUID = UUID.randomUUID()
      val movementUuid4: UUID = UUID.randomUUID()

      val expiredMovement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, movementUuid1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement("T1", "coffee start", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), -1, movementUuid2, createdBy = Some("robin"))
      val unexpiredMovement1 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:30").millisSinceEpoch), -1, movementUuid3, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:40").millisSinceEpoch), -1, movementUuid4, createdBy = Some("bruce"))

      val now_is_20170102_0200: () => SDateLike = () => SDate("2017-01-02T02:00")
      val expireAfterOneDay: () => SDateLike = () => now_is_20170102_0200().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now_is_20170102_0200, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(expiredMovement1, expiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(expiredMovement1, expiredMovement2)))

      actor ! AddStaffMovements(Seq(unexpiredMovement1, unexpiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(unexpiredMovement1, unexpiredMovement2)))

      actor ! GetState
      val expected = Set(unexpiredMovement1, unexpiredMovement2)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge movements created more than the specified expiry period ago when requested via a point in time actor" in {
      val expiredMovement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, UUID.randomUUID(), createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement("T1", "coffee start", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), -1, UUID.randomUUID(), createdBy = Some("robin"))
      val unexpiredMovement1 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:30").millisSinceEpoch), -1, UUID.randomUUID(), createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:40").millisSinceEpoch), -1, UUID.randomUUID(), createdBy = Some("bruce"))

      val now_is_20170102_0200: () => SDateLike = () => SDate("2017-01-02T02:00")
      val expireAfterOneDay: () => SDateLike = () => now_is_20170102_0200().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now_is_20170102_0200, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(expiredMovement1, expiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(expiredMovement1, expiredMovement2)))

      actor ! AddStaffMovements(Seq(unexpiredMovement1, unexpiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(unexpiredMovement1, unexpiredMovement2)))

      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsReadActor], now_is_20170102_0200(), expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(unexpiredMovement1, unexpiredMovement2)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "keep pairs of movements when only one was created more than the specified expiry period ago when requested via a point in time actor" in {
      val pair1 = UUID.randomUUID()
      val expiredMovement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, pair1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement("T1", "lunch end", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), 1, pair1, createdBy = Some("robin"))
      val pair2 = UUID.randomUUID()
      val unexpiredMovement1 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:30").millisSinceEpoch), -1, pair2, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement("T1", "supper enmd", MilliDate(SDate(s"2017-01-01T21:40").millisSinceEpoch), 1, pair2, createdBy = Some("ed"))

      val now_is_20170102_0200: () => SDateLike = () => SDate("2017-01-02T01:00")
      val expireAfterOneDay: () => SDateLike = () => now_is_20170102_0200().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now_is_20170102_0200, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(expiredMovement1, expiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(expiredMovement1, expiredMovement2)))

      actor ! AddStaffMovements(Seq(unexpiredMovement1, unexpiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(unexpiredMovement1, unexpiredMovement2)))

      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsReadActor], now_is_20170102_0200(), expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(expiredMovement1, expiredMovement2, unexpiredMovement1, unexpiredMovement2)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge pairs of movements where both were created more than the specified expiry period ago when requested via a point in time actor" in {
      val pair1 = UUID.randomUUID()
      val expiredMovement1 = StaffMovement("T1", "lunch start", MilliDate(SDate(s"2017-01-01T00:00").millisSinceEpoch), -1, pair1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement("T1", "lunch end", MilliDate(SDate(s"2017-01-01T01:15").millisSinceEpoch), 1, pair1, createdBy = Some("robin"))
      val pair2 = UUID.randomUUID()
      val unexpiredMovement1 = StaffMovement("T1", "supper start", MilliDate(SDate(s"2017-01-01T21:30").millisSinceEpoch), -1, pair2, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement("T1", "supper end", MilliDate(SDate(s"2017-01-01T21:40").millisSinceEpoch), 1, pair2, createdBy = Some("ed"))

      val now_is_20170102_0200: () => SDateLike = () => SDate("2017-01-02T21:35")
      val expireAfterOneDay: () => SDateLike = () => now_is_20170102_0200().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now_is_20170102_0200, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(expiredMovement1, expiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(expiredMovement1, expiredMovement2)))

      actor ! AddStaffMovements(Seq(unexpiredMovement1, unexpiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(unexpiredMovement1, unexpiredMovement2)))

      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsReadActor], now_is_20170102_0200(), expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(unexpiredMovement1, unexpiredMovement2)

      val result = expectMsgPF(1 second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }
  }

}
