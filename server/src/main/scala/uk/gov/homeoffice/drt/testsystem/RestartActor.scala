package uk.gov.homeoffice.drt.testsystem

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.pattern.{StatusReply, ask}
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.stream.KillSwitch
import akka.util.Timeout
import uk.gov.homeoffice.drt.testsystem.RestartActor.{AddResetActors, StartTestSystem}
import uk.gov.homeoffice.drt.testsystem.TestActors.ResetData

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

object RestartActor {
  case object StartTestSystem

  case class AddResetActors(actors: Iterable[ActorRef])
}

class RestartActor(startSystem: () => List[KillSwitch]) extends Actor with ActorLogging {

  private lazy val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(context.system)

  private var currentKillSwitches: List[KillSwitch] = List()
  private var actorsToReset: Seq[ActorRef] = Seq.empty
  private var maybeReplyTo: Option[ActorRef] = None

  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case AddResetActors(actors) =>
      actorsToReset = actorsToReset ++ actors

    case ResetData =>
      maybeReplyTo = Option(sender())
      log.info(s"About to shut down everything. Pressing kill switches")

      currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
        log.info(s"Kill switch ${idx + 1}")
        ks.shutdown()
      }

      resetInMemoryData()

      val resetFutures = actorsToReset
        .map(_.ask(ResetData)(new Timeout(3.second)).recover {
          case t =>
            log.error(s"Failed to reset actor: ${t.getMessage}")
            Status.Failure(t)
        })

      Future.sequence(resetFutures).onComplete { _ =>
        log.info(s"Restarting system")
        startTestSystem()
        maybeReplyTo.foreach { k =>
          log.info(s"Sending Ack to sender")
          k ! StatusReply.Ack
        }
        maybeReplyTo = None
      }

    case Status.Success(_) =>
      log.info(s"Got a Status acknowledgement from InMemoryJournalStorage")

    case Status.Failure(t) =>
      log.error(s"Got a failure message: ${t.getMessage}")

    case StartTestSystem =>
      startTestSystem()

    case u =>
      log.error(s"Received unexpected message: ${u.getClass}")
  }

  private def startTestSystem(): Unit = currentKillSwitches = startSystem()

  private def resetInMemoryData(): Unit = persistenceTestKit.clearAll()
}
