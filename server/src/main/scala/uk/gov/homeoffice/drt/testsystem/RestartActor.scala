package uk.gov.homeoffice.drt.testsystem

import akka.actor.{Actor, ActorLogging, ActorRef, Status, typed}
import akka.pattern.{StatusReply, ask}
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.stream.KillSwitch
import akka.util.Timeout
import drt.server.feeds.Feed.FeedTick
import services.crunch.CrunchSystem
import uk.gov.homeoffice.drt.testsystem.RestartActor.{AddResetActors, StartTestSystem}
import uk.gov.homeoffice.drt.testsystem.TestActors.ResetData

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

object RestartActor {
  case object StartTestSystem

  case class AddResetActors(actors: Iterable[ActorRef])
}

class RestartActor(startSystem: () => Future[Option[CrunchSystem[typed.ActorRef[FeedTick]]]]) extends Actor with ActorLogging {

  private lazy val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(context.system)

  private var currentKillSwitches: List[KillSwitch] = List()
  private var actorsToReset: Seq[ActorRef] = Seq.empty
  private var maybeReplyTo: Option[ActorRef] = None

  implicit val ec: ExecutionContextExecutor = context.dispatcher

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
        .map(_.ask(ResetData)(new Timeout(3.second)))

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
      val replyTo = sender()
      startTestSystem().foreach (replyTo ! _)

    case u =>
      log.error(s"Received unexpected message: ${u.getClass}")
  }

  private def startTestSystem(): Future[Option[CrunchSystem[typed.ActorRef[FeedTick]]]] =
    startSystem().map { cs =>
      cs.foreach { c =>
        currentKillSwitches = c.killSwitches
      }
      cs
    }

  private def resetInMemoryData(): Unit = persistenceTestKit.clearAll()
}
