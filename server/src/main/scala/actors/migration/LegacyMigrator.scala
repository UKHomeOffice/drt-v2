package actors.migration

import actors.StreamingJournalLike
import actors.migration.LegacyStreamingJournalMigrationActor.MigrationStatus
import actors.minutes.MinutesActorLike.{CrunchMinutesMigrationUpdate, FlightsMigrationUpdate}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future

case class LegacyMigrator(
                           updateFlightsFn: FlightsMigrationUpdate,
                           updateCrunchMinutesFn: CrunchMinutesMigrationUpdate,
                           journalType: StreamingJournalLike,
                           legacyPersistenceId: String,
                           firstSequenceNumber: Long
                          )(implicit system: ActorSystem, timeout: Timeout) {

  val flightsRouterMigrationActor: ActorRef = system.actorOf(
    Props(new FlightsRouterMigrationActor(updateFlightsFn)),
    s"FlightsRouterMigrationActor$legacyPersistenceId"
  )
  val crunchMinutesMigratorActor: ActorRef = system.actorOf(
    Props(new CrunchMinutesRouterMigrationActor(updateCrunchMinutesFn)),
    s"CrunchMinutesRouterMigrationActor$legacyPersistenceId"
  )
  val migrationActor: ActorRef = system.actorOf(
    Props(new LegacyStreamingJournalMigrationActor(
      journalType,
      firstSequenceNumber,
      flightsRouterMigrationActor,
      crunchMinutesMigratorActor,
      legacyPersistenceId
    )),
    s"FlightsMigrationActor$legacyPersistenceId"
  )

  def status(): Future[MigrationStatus] = migrationActor.ask(GetMigrationStatus).mapTo[MigrationStatus]

  def start(): Unit = migrationActor ! StartMigration

  def stop(): Unit = migrationActor ! StopMigration

}
