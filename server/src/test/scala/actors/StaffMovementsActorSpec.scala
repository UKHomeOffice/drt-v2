package actors


import actors.persistent.staffing._
import akka.actor.{PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.{StaffMovement, StaffMovements}
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import java.util.UUID
import scala.concurrent.duration._


object PersistenceHelper {
  val dbLocation = "target/test"
}

class StaffMovementsActorSpec extends CrunchTestLike with ImplicitSender {
  sequential
  isolated

  "StaffMovementsActor" should {
    val now: () => SDateLike = () => SDate("2017-01-01T23:59")
    val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

    "Send subscriber the affected milliseconds when adding and removing movements" in {
      val uuid = newUuidString
      val staffMovements = StaffMovements(Seq(StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, uuid, createdBy = Some("batman"))))

      val actor = system.actorOf(Props(new StaffMovementsActor(now, expireAfterOneDay, 1440)), "movementsActor1")

      val probe = TestProbe("movements-subscriber-test")
      actor ! probe.ref

      Thread.sleep(100)

      actor ! AddStaffMovements(staffMovements.movements)
      probe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2017, 1, 1), 0, 1440))

      actor ! RemoveStaffMovements(uuid)
      probe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2017, 1, 1), 0, 1440))

      true
    }

    "remember a movement added before a shutdown" in {
      val movementUuid1 = newUuidString
      val staffMovements = StaffMovements(Seq(StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, movementUuid1, createdBy = Some("batman"))))

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
      val movementUuid1 = newUuidString
      val movementUuid2 = newUuidString

      val movement1 = StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement(T1, "coffee start", SDate(s"2017-01-01T01:15").millisSinceEpoch, -1, movementUuid2, createdBy = Some("robin"))
      val staffMovements = StaffMovements(Seq(movement1, movement2))

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(staffMovements.movements)
      expectMsg(AddStaffMovementsAck(staffMovements.movements))

      actor ! RemoveStaffMovements(movementUuid1)
      expectMsg(RemoveStaffMovementsAck(movementUuid1))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[StaffMovementsActorBase], now, expireAfterOneDay), "movementsActor2")

      newActor ! GetState
      val expected = Set(movement2)

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "remember multiple added movements and correctly forget removed movements after a restart" in {
      val movementUuid1 = newUuidString
      val movementUuid2 = newUuidString
      val movementUuid3 = newUuidString
      val movementUuid4 = newUuidString

      val movement1 = StaffMovement(T1, "lunch start", SDate("2017-01-01T00:00").millisSinceEpoch, -1, movementUuid1, createdBy = Some("batman"))
      val movement2 = StaffMovement(T1, "coffee start", SDate("2017-01-01T01:15").millisSinceEpoch, -1, movementUuid2, createdBy = Some("robin"))
      val movement3 = StaffMovement(T1, "supper start", SDate("2017-01-01T21:30").millisSinceEpoch, -1, movementUuid3, createdBy = Some("bruce"))
      val movement4 = StaffMovement(T1, "supper start", SDate("2017-01-01T21:40").millisSinceEpoch, -1, movementUuid4, createdBy = Some("bruce"))

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

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge movements created more than the specified expiry period ago" in {
      val movementUuid1 = newUuidString
      val movementUuid2 = newUuidString
      val movementUuid3 = newUuidString
      val movementUuid4 = newUuidString

      val expiredMovement1 = StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, movementUuid1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement(T1, "coffee start", SDate(s"2017-01-01T01:15").millisSinceEpoch, -1, movementUuid2, createdBy = Some("robin"))
      val unexpiredMovement1 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:30").millisSinceEpoch, -1, movementUuid3, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:40").millisSinceEpoch, -1, movementUuid4, createdBy = Some("bruce"))

      val now_is_20170102_0200: () => SDateLike = () => SDate("2017-01-02T02:00")
      val expireAfterOneDay: () => SDateLike = () => now_is_20170102_0200().addDays(-1)

      val actor = system.actorOf(Props(classOf[StaffMovementsActorBase], now_is_20170102_0200, expireAfterOneDay), "movementsActor1")

      actor ! AddStaffMovements(Seq(expiredMovement1, expiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(expiredMovement1, expiredMovement2)))

      actor ! AddStaffMovements(Seq(unexpiredMovement1, unexpiredMovement2))
      expectMsg(AddStaffMovementsAck(Seq(unexpiredMovement1, unexpiredMovement2)))

      actor ! GetState
      val expected = Set(unexpiredMovement1, unexpiredMovement2)

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge movements created more than the specified expiry period ago when requested via a point in time actor" in {
      val expiredMovement1 = StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, newUuidString, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement(T1, "coffee start", SDate(s"2017-01-01T01:15").millisSinceEpoch, -1, newUuidString, createdBy = Some("robin"))
      val unexpiredMovement1 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:30").millisSinceEpoch, -1, newUuidString, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:40").millisSinceEpoch, -1, newUuidString, createdBy = Some("bruce"))

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

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "keep pairs of movements when only one was created more than the specified expiry period ago when requested via a point in time actor" in {
      val pair1 = newUuidString
      val expiredMovement1 = StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, pair1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement(T1, "lunch end", SDate(s"2017-01-01T01:15").millisSinceEpoch, 1, pair1, createdBy = Some("robin"))
      val pair2 = newUuidString
      val unexpiredMovement1 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:30").millisSinceEpoch, -1, pair2, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement(T1, "supper enmd", SDate(s"2017-01-01T21:40").millisSinceEpoch, 1, pair2, createdBy = Some("ed"))

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

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }

    "purge pairs of movements where both were created more than the specified expiry period ago when requested via a point in time actor" in {
      val pair1 = newUuidString
      val expiredMovement1 = StaffMovement(T1, "lunch start", SDate(s"2017-01-01T00:00").millisSinceEpoch, -1, pair1, createdBy = Some("batman"))
      val expiredMovement2 = StaffMovement(T1, "lunch end", SDate(s"2017-01-01T01:15").millisSinceEpoch, 1, pair1, createdBy = Some("robin"))
      val pair2 = newUuidString
      val unexpiredMovement1 = StaffMovement(T1, "supper start", SDate(s"2017-01-01T21:30").millisSinceEpoch, -1, pair2, createdBy = Some("bruce"))
      val unexpiredMovement2 = StaffMovement(T1, "supper end", SDate(s"2017-01-01T21:40").millisSinceEpoch, 1, pair2, createdBy = Some("ed"))

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

      val result = expectMsgPF(1.second) {
        case StaffMovements(movements) => movements.toSet
      }

      result === expected
    }
  }

  private def newUuidString = UUID.randomUUID().toString
}
