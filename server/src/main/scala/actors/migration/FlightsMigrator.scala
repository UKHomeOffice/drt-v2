package actors.migration

import actors.StreamingJournalLike
import actors.daily.RequestAndTerminateActor
import actors.minutes.MinutesActorLike.FlightsMigrationUpdate
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.Future

case class FlightsMigrator(
                             updateFlightsFn: FlightsMigrationUpdate,
                             journalType: StreamingJournalLike
                           )(implicit system: ActorSystem, timeout: Timeout) {

  def status(): Future[Long] = flightsMigrationActor.ask(MigrationStatus).mapTo[Long]

  val flightsRouterMigrationActor: ActorRef = system.actorOf(Props(new FlightsRouterMigrationActor(updateFlightsFn)))
  val flightsMigrationActor: ActorRef = system
    .actorOf(FlightsMigrationActor.props(journalType, flightsRouterMigrationActor))

  def start(): Unit = flightsMigrationActor ! StartMigration

  def stop(): Unit = flightsMigrationActor ! StopMigration

}
