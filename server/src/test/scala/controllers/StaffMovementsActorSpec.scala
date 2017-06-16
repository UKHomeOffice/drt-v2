package controllers

import java.util.UUID

import akka.util.Timeout
import org.specs2.mutable.Specification
import drt.shared.{MilliDate, StaffMovement}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._

class StaffMovementsActorSpec extends Specification {
  sequential

  "StaffMovementsPersistenceApi" should {
    "allow setting and getting of staff movements" in new AkkaTestkitSpecs2SupportForPersistence("target/testStaffMovements") {

      val staffMovementsApi = new StaffMovementsPersistence {
        override implicit val timeout: Timeout = Timeout(5 seconds)

        val actorSystem = system
      }

      private val uuid: UUID = UUID.randomUUID()
      staffMovementsApi.saveStaffMovements(Seq(
        StaffMovement("T1", "is81", MilliDate(0L), -1, uuid)
      ))

      awaitAssert({
        val resultFuture = staffMovementsApi.getStaffMovements()
        val result = Await.result(resultFuture, 1 seconds)
        assert(Seq(StaffMovement("T1", "is81", MilliDate(0L), -1, uuid)) == result)
      }, 2 seconds)
    }
  }
}
