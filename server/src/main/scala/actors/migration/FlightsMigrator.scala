package actors.migration

import actors.StreamingJournalLike
import actors.migration.FlightsMigrationActor.MigrationStatus
import actors.minutes.MinutesActorLike.FlightsMigrationUpdate
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future

case class FlightsMigrator(
                            updateFlightsFn: FlightsMigrationUpdate,
                            journalType: StreamingJournalLike,
                            legacyPersistenceId: String,
                            firstSequenceNumber: Long
                          )(implicit system: ActorSystem, timeout: Timeout) {

  val flightsRouterMigrationActor: ActorRef = system.actorOf(
    Props(new FlightsRouterMigrationActor(updateFlightsFn)),
    s"FlightsRouterMigrationActor$legacyPersistenceId"
  )
  val flightsMigrationActor: ActorRef = system.actorOf(
    Props(new FlightsMigrationActor(journalType, firstSequenceNumber, flightsRouterMigrationActor, legacyPersistenceId)),
    s"FlightsMigrationActor$legacyPersistenceId"
  )

  def status(): Future[MigrationStatus] = flightsMigrationActor.ask(GetMigrationStatus).mapTo[MigrationStatus]

  def start(): Unit = flightsMigrationActor ! StartMigration

  def stop(): Unit = flightsMigrationActor ! StopMigration

}
