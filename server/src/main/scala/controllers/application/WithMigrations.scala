package controllers.application

import actors.{DbStreamingJournal, PostgresTables}
import actors.daily.RequestAndTerminateActor
import actors.migration._
import actors.minutes.MinutesActorLike.FlightsMigrationUpdate
import akka.actor.{ActorRef, Props}
import controllers.Application
import drt.auth.Debug
import play.api.http.HttpEntity
import play.api.mvc.{Action, AnyContent, ResponseHeader, Result}
import services.SDate
import slick.jdbc.SQLActionBuilder
import slickdb.AkkaPersistenceSnapshotTable

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


trait WithMigrations {

  self: Application =>

  lazy val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "migration-request-and-terminate")
  lazy val flightsUpdateFn: FlightsMigrationUpdate = FlightsRouterMigrationActor
    .updateFlights(requestAndTerminateActor, TerminalDayFlightMigrationActor.props)

  def firstSequenceNumber(legacyPersistenceId: String): Long = {
    val table = AkkaPersistenceSnapshotTable(PostgresTables)
    import table.tables.profile.api._

    val query: SQLActionBuilder =
      sql"""SELECT MIN(sequence_number)
            FROM journal
            WHERE persistence_id=$legacyPersistenceId
        """
    val eventualInts = table.db.run(query.as[Long])
    Await.result(eventualInts.map(_.headOption.getOrElse(0L)), 1 second)
  }

  lazy val legacy1FlightsMigrator: FlightsMigrator = FlightsMigrator(flightsUpdateFn, DbStreamingJournal, FlightsMigrationActor.legacy1PersistenceId, firstSequenceNumber(FlightsMigrationActor.legacy1PersistenceId))
  lazy val legacy2FlightsMigrator: FlightsMigrator = FlightsMigrator(flightsUpdateFn, DbStreamingJournal, FlightsMigrationActor.legacy2PersistenceId, firstSequenceNumber(FlightsMigrationActor.legacy2PersistenceId))

  def startFlightMigration(legacyType: Int): Action[AnyContent] = authByRole(Debug) {
    Action {
      if (legacyType == 1)
        legacy1FlightsMigrator.start()
      else if (legacyType == 2)
        legacy2FlightsMigrator.start()

      Ok("""<a href="/migrations/flights">Status</a>""").as("text/html")
    }
  }

  def stopFlightMigration(legacyType: Int): Action[AnyContent] = authByRole(Debug) {
    Action {
      if (legacyType == 1)
        legacy1FlightsMigrator.stop()
      else if (legacyType == 2)
        legacy2FlightsMigrator.stop()

      Ok("""<a href="/migrations/flights">Status</a>""").as("text/html")
    }
  }

  def flightMigrationStatus(): Action[AnyContent] = authByRole(Debug) {
    Action.async {
      val eventual1Status = legacy1FlightsMigrator.status()
      val eventual2Status = legacy2FlightsMigrator.status()
      for {
        status1 <- eventual1Status
        status2 <- eventual2Status
      } yield {
        Result(header = ResponseHeader(NO_CONTENT), body = HttpEntity.NoEntity)
        Ok(
          s"""
             |<p>
             |CrunchState data: ${if (status1.isRunning) "Running" else "Not running"}, sequence no ${status1.seqNr}, created at: ${SDate(status1.createdAt).toISOString()}</br>
             |<form method="POST" action="/migrations/flights/1/start">
             |  <input type="submit" value="Start">
             |</form>
             |<form method="POST" action="/migrations/flights/1/stop">
             |  <input type="submit" value="Stop">
             |</form>
             |</p>
             |<p>
             |FlightsState data: ${if (status2.isRunning) "Running" else "Not running"}, sequence no ${status2.seqNr}, created at: ${SDate(status2.createdAt).toISOString()}</br>
             |<form method="POST" action="/migrations/flights/2/start">
             |  <input type="submit" value="Start">
             |</form>
             |<form method="POST" action="/migrations/flights/2/stop">
             |  <input type="submit" value="Stop">
             |</form>
             |</p>
             |""".stripMargin).as("text/html")
      }
    }
  }
}
