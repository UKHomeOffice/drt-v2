package services.crunch.deskrecs

import drt.shared.CrunchApi
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class DeskRecsTest extends AnyWordSpec with Matchers {
  "minDesksByWorkload" should {
    "give all zeros when there are no passengers and zero min desks" in {
      val mins = DeskRecs.minDesksForWorkload(
        minDesks = Seq.fill(60)(0),
        pax = Seq.fill(60)(0)
      )
      mins.forall(_ == 0) shouldBe true
    }

    "give ones for 15 minute periods where there are any passengers and zero min desks" in {
      val mins = DeskRecs.minDesksForWorkload(
        minDesks = Seq.fill(60)(0),
        pax = Seq.fill(15)(0) ++ Seq.fill(7)(0) ++ Seq(1) ++ Seq.fill(7)(0) ++ Seq.fill(15)(0) ++ Seq.fill(15)(1)
      )
      mins shouldBe Seq.fill(15)(0) ++ Seq.fill(15)(1) ++ Seq.fill(15)(0) ++ Seq.fill(15)(1)
    }

    "give max of min desks for 15 minute periods where there are passengers and min desks greater than 1" in {
      val mins = DeskRecs.minDesksForWorkload(
        minDesks = Seq.fill(60)(3),
        pax = Seq.fill(15)(0) ++ Seq.fill(7)(0) ++ Seq(1) ++ Seq.fill(7)(0) ++ Seq.fill(15)(0) ++ Seq.fill(15)(1)
      )
      mins shouldBe Seq.fill(15)(3) ++ Seq.fill(15)(3) ++ Seq.fill(15)(3) ++ Seq.fill(15)(3)
    }
  }

  "paxForQueue" should {
    "return pax numbers for the correct queue given a passengersminute provider" in {
      val provider: (SDateLike, SDateLike, Terminal) => Future[Map[TQM, CrunchApi.PassengersMinute]] =
        (_, _, _) => Future.successful(Map(
          TQM(T1, EeaDesk, 120000l) -> CrunchApi.PassengersMinute(T1, EeaDesk, 120000l, Seq.fill(5)(5), None),
          TQM(T1, EeaDesk, 60000l) -> CrunchApi.PassengersMinute(T1, EeaDesk, 60000l, Seq.fill(6)(5), None),
          TQM(T1, EeaDesk, 0l) -> CrunchApi.PassengersMinute(T1, EeaDesk, 0l, Seq.fill(7)(5), None),
          TQM(T1, EGate, 120000l) -> CrunchApi.PassengersMinute(T1, EGate, 120000l, Seq.fill(15)(5), None),
          TQM(T1, EGate, 60000l) -> CrunchApi.PassengersMinute(T1, EGate, 60000l, Seq.fill(15)(5), None),
          TQM(T1, EGate, 0l) -> CrunchApi.PassengersMinute(T1, EGate, 0l, Seq.fill(15)(5), None),
        ))
      val paxForT1 = DeskRecs.paxForQueue(provider)
      val paxForEeaDesk = Await.result(paxForT1(T1)(0l to 120000 by 60000, EeaDesk), 1.second)

      paxForEeaDesk shouldBe Seq(7, 6, 5)
    }
  }
}
