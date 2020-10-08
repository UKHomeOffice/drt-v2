package actors.migration

import actors.StreamingJournalLike
import actors.migration.FlightsMigrationActor.MigrationStatus
import actors.minutes.MinutesActorLike.CrunchMinutesMigrationUpdate
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future

case class MinutesMigrator(updateQueueMinutesFn: CrunchMinutesMigrationUpdate,
                           //                           updateStaffMinutesFn: CrunchMinutesMigrationUpdate,
                           journalType: StreamingJournalLike,
                           legacyPersistenceId: String,
                           firstSequenceNumber: Long,
                          )
                          (implicit system: ActorSystem, timeout: Timeout) {

  val minutesRouterMigrationActor: ActorRef = system.actorOf(Props(new CrunchMinutesRouterMigrationActor(updateQueueMinutesFn)), s"MinutesRouterMigrationActor$legacyPersistenceId")
  val minutesMigrationActor: ActorRef = system.actorOf(
    MinutesMigrationActor.props(journalType, firstSequenceNumber, minutesRouterMigrationActor, legacyPersistenceId),
    s"MinutesMigrationActor$legacyPersistenceId"
  )

  def status(): Future[MigrationStatus] = minutesMigrationActor.ask(GetMigrationStatus).mapTo[MigrationStatus]

  def start(): Unit = minutesMigrationActor ! StartMigration

  def stop(): Unit = minutesMigrationActor ! StopMigration

}
