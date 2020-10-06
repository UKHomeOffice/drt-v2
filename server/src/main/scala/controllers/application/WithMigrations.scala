package controllers.application

import actors.DbStreamingJournal
import actors.daily.RequestAndTerminateActor
import actors.migration._
import actors.minutes.MinutesActorLike.FlightsMigrationUpdate
import akka.actor.{ActorRef, Props}
import controllers.Application
import drt.auth.Debug
import play.api.mvc.{Action, AnyContent}
import services.SDate


trait WithMigrations {

  self: Application =>

  lazy val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "migration-request-and-terminate")
  lazy val flightsUpdateFn: FlightsMigrationUpdate = FlightsRouterMigrationActor
    .updateFlights(requestAndTerminateActor, TerminalDayFlightMigrationActor.props)

  lazy val flightsMigrator: FlightsMigrator = FlightsMigrator(flightsUpdateFn, DbStreamingJournal)

  def startFlightMigration(): Action[AnyContent] = authByRole(Debug) {
    Action {
      flightsMigrator.start()

      Ok("Starting migration")
    }
  }

  def flightMigrationStatus(): Action[AnyContent] = authByRole(Debug) {
    Action.async {
      flightsMigrator.status().map(status =>

        Ok(s"Sequence no ${status.seqNr}, created at: ${SDate(status.createdAt).toISOString()}")
      )
    }
  }

  def stopFlightMigration(): Action[AnyContent] = authByRole(Debug) {
    Action {
      flightsMigrator.stop()

      Ok("Stopping migration")
    }
  }


}
