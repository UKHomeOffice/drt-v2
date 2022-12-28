package controllers.application

import actors.debug.{DebugFlightsActor, MessageQuery, MessageResponse}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import akka.actor.Props
import akka.pattern.ask
import controllers.Application
import play.api.mvc.{Action, AnyContent}
import services.ActorTree
import uk.gov.homeoffice.drt.auth.Roles.Debug
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.FlightsWithSplitsDiffMessage
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.SortedMap


trait WithDebug {

  self: Application =>

  def getActorTree: Action[AnyContent] = authByRole(Debug) {
    Action { _ =>
      Ok(ActorTree.get().toString)
    }
  }

  def getMessagesForFlightPersistenceIdAtTime(persistenceId: String, dateString: String, messages: Int): Action[AnyContent] = authByRole(Debug) {
    Action.async { _ =>
      val pit = SDate(dateString)
      val persistenceIds = SortedMap(
        "ACL" -> AclForecastArrivalsActor.persistenceId,
        "Port Forecast" -> PortForecastArrivalsActor.persistenceId,
        "Cirium Live" -> CirriumLiveArrivalsActor.persistenceId,
        "Port Live" -> PortLiveArrivalsActor.persistenceId,
        "Crunch State" -> "crunch-state",
        "Flight State" -> "flight-state",
      ) ++ airportConfig.terminals.map(t => {
        "Terminal Day Flight (for snapshot day)" -> f"terminal-flights-${t.toString.toLowerCase}-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02d"
      })

      if (persistenceIds.keys.exists(_ == persistenceId)) throw new Exception("Invalid actor")

      val actor = system.actorOf(Props(new DebugFlightsActor(persistenceId, Option(pit.millisSinceEpoch))))

      val actorSelection = persistenceIds.map {
        case (feed, id) =>
          s"<a href='/debug/flights/$id/${pit.toISOString()}/$messages'>$feed</a></br>"
      }.mkString("\n")

      val timeNavigation =
        s"<a href='/debug/flights/$persistenceId/${pit.addDays(-1).toISOString()}/$messages'>-1 day</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addHours(-1).toISOString()}/$messages'>-1 hour</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addMinutes(-1).toISOString()}/$messages'>-1 mins</a> " +
          pit.toISOString() +
          s" <a href='/debug/flights/$persistenceId/${pit.addMinutes(1).toISOString()}/$messages'>+1 mins</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addHours(1).toISOString()}/$messages'>+1 hour</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addDays(1).toISOString()}/$messages'>+1 day</a> "
      val numMessagesNavigation =
        s"<a href='/debug/flights/$persistenceId/${pit.toISOString()}/10'>10</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString()}/50'>50</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString()}/500'>500</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString()}/1000'>1000</a> "

      val navigation =
        "<h3>Actor Selection</h3>" + actorSelection +
          "<h3>Time</h3>" + timeNavigation +
          "<h3>Messages to show</h3>" + numMessagesNavigation

      (actor ? MessageQuery(messages)).map {
        case m: MessageResponse =>

          val debugHtml = m.messages.map {
            case f: FlightsWithSplitsDiffMessage =>

              val heading = s"<h3>Created at: ${f.createdAt.map(SDate(_).toISOString()).getOrElse("Missing")}</h3>"
              val updates = if (f.updates.nonEmpty) {

                "<table border='1' cellpadding='5' cellspacing='0'><tr>" +
                  s"<td  colspan='14'>Updates: </td>" +
                  "</tr>" +
                  "<tr>" +
                  "<td>Flight code</td>" +
                  "<td>Terminal</td>" +
                  "<td>Scheduled</td>" +
                  "<td>Est</td>" +
                  "<td>Est Chox</td>" +
                  "<td>Act Chox</td>" +
                  "<td>Est PCP</td>" +
                  "<td>Status</td>" +
                  "<td>Gate</td>" +
                  "<td>Stand</td>" +
                  "<td>Act Pax</td>" +
                  "<td>Api Pax</td>" +
                  "<td>Max Pax</td>" +
                  "<td>Tran Pax</td>" +
                  "</tr>" +
                  f.updates.map(a => {
                    "<tr>" +
                      "<td>" + a.getFlight.getIATA + "</td>" +
                      "<td>" + a.getFlight.getTerminal + "</td>" +
                      "<td>" + SDate(a.getFlight.getScheduled).toISOString + "</td>" +
                      "<td>" + a.getFlight.estimated.map(SDate(_).toISOString()).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.estimatedChox.map(SDate(_).toISOString()).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.actualChox.map(SDate(_).toISOString()).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.pcpTime.map(SDate(_).toISOString()).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.status.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.gate.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.stand.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.actPax.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.apiPax.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.maxPax.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.tranPax.getOrElse("-") + "</td>" +
                      "<tr>"

                  }).mkString("\n") +
                  "</table>"
              } else "No Updates </br>"
              val removals = if (f.removals.nonEmpty) {

                "<table border='1' cellpadding='5' cellspacing='0'><tr>" +
                  "<tr>" +
                  s"<td colspan='14'>Removals: </td>" +
                  "</tr>" +
                  "<tr>" +
                  s"<td>Scheduled</td>" +
                  s"<td>Terminal</td>" +
                  s"<td>Number</td>" +
                  "</tr>" +
                  f.removals.map(r => {
                    s"<tr><td>${SDate(r.getScheduled).toISOString()}</td><td>${r.getTerminalName}</td><td>${r.getNumber}</td></tr>"
                  }).mkString("\n") +
                  "</table>"
              } else "No removals </br>"

              heading + updates + removals

          }.mkString("\n")

          val heading = s"<h1>$persistenceId</h1>"
          Ok(heading + navigation + "</br>" + debugHtml).as("text/html")
      }
    }
  }
}
