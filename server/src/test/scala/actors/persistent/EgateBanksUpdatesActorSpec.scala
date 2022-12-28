package actors.persistent

import actors.persistent.staffing.GetState
import akka.actor.{PoisonPill, Props}
import akka.pattern.ask
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class EgateBanksUpdatesActorSpec extends CrunchTestLike {
  "Given an egate banks update actor" >> {
    val defaultUpdate = EgateBanksUpdate(100L, IndexedSeq(EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true, false))))
    val defaults: Map[Terminal, EgateBanksUpdates] = Map(T1 -> EgateBanksUpdates(List(defaultUpdate)))
    val actor = system.actorOf(Props(new EgateBanksUpdatesActor(() => SDate.now(), defaults, 0, 1440, 5)))

    "Given no updates, when I ask for its state I should receive the default gates" >> {
      val result = Await.result(actor.ask(GetState).mapTo[PortEgateBanksUpdates], 1.second)
      actor ! PoisonPill
      result === PortEgateBanksUpdates(defaults)
    }

    "Given an update, when I ask for its state I should see the update included" >> {
      val update = EgateBanksUpdate(150L, IndexedSeq(EgateBank(IndexedSeq(false, true, false))))
      val eventualState = actor
        .ask(SetEgateBanksUpdate(T1, 150L, update))
        .flatMap(_ => actor.ask(GetState).mapTo[PortEgateBanksUpdates])
      val result = Await.result(eventualState, 1.second)
      actor ! PoisonPill
      result === PortEgateBanksUpdates(Map(T1 -> EgateBanksUpdates(List(defaultUpdate, update))))
    }

    "Given a removal of an update, when I ask for its state I should no longer see the update" >> {
      val update = EgateBanksUpdate(150L, IndexedSeq(EgateBank(IndexedSeq(false, true, false))))
      val eventualState = actor
        .ask(SetEgateBanksUpdate(T1, 150L, update))
        .flatMap(_ => actor
          .ask(DeleteEgateBanksUpdates(T1, 150L))
          .flatMap(_ => actor.ask(GetState).mapTo[PortEgateBanksUpdates])
        )
      val result = Await.result(eventualState, 1.second)
      actor ! PoisonPill
      result === PortEgateBanksUpdates(Map(T1 -> EgateBanksUpdates(List(defaultUpdate))))
    }
  }
}
