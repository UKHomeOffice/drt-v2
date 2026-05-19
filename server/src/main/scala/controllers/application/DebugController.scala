package controllers.application

import actors.debug.{DebugFlightsActor, DebugStatsQuery, DebugStatsResponse, MessageQuery, MessageResponse}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CiriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.ask
import com.google.inject.Inject
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import scalapb.GeneratedMessage
import services.ActorTree
import uk.gov.homeoffice.drt.auth.Roles.Debug
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.ApiFeedSource
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.FlightsWithSplitsDiffMessage
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.Try


class DebugController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  private val liveFeedArrivalsDiffClass =
    "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.LiveFeedArrivalsDiffMessage"
  private val forecastFeedArrivalsDiffClass =
    "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.ForecastFeedArrivalsDiffMessage"
  private val feedDiffMessageClasses: Set[String] = Set(
    liveFeedArrivalsDiffClass,
    forecastFeedArrivalsDiffClass,
  )

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.toString).replace("+", "%20")

  private def traceActorIdsForPit(pit: SDateLike): Seq[(String, String)] = {
    val day = f"${pit.getFullYear}-${pit.getMonth}%02d-${pit.getDate}%02d"

    val staticActorIds = Seq(
      "ACL (status actor)" -> AclForecastArrivalsActor.persistenceId,
      "Port Forecast (status actor)" -> PortForecastArrivalsActor.persistenceId,
      "Cirium Live (status actor)" -> CiriumLiveArrivalsActor.persistenceId,
      "Port Live (status actor)" -> PortLiveArrivalsActor.persistenceId,
      "Crunch State" -> "crunch-state",
      "Flight State" -> "flight-state",
    )

    val dayActorIds = Seq(
      "Manifests (day)" -> s"manifests-$day",
      "Live Feed Arrivals (all terminals)" -> s"live-feed-arrivals--$day",
      "Live Base Feed Arrivals (all terminals)" -> s"live-base-feed-arrivals--$day",
      "Terminal Flights (all terminals)" -> s"terminal-flights--$day",
    )

    val terminalActorIds = airportConfig.terminalsForDate(pit.toLocalDate).toSeq.sortBy(_.toString).flatMap { t =>
      val terminal = t.toString.toLowerCase
      Seq(
        s"Terminal Flights ${t.toString}" -> s"terminal-flights-$terminal-$day",
        s"ACL Feed Arrivals ${t.toString}" -> s"acl-feed-arrivals-$terminal-$day",
        s"Forecast Feed Arrivals ${t.toString}" -> s"forecast-feed-arrivals-$terminal-$day",
        s"Live Feed Arrivals ${t.toString}" -> s"live-feed-arrivals-$terminal-$day",
      )
    }

    (staticActorIds ++ dayActorIds ++ terminalActorIds).distinct
  }

  private def flightsUiActorIdsForPit(pit: SDateLike): Seq[(String, String)] = {
    val day = f"${pit.getFullYear}-${pit.getMonth}%02d-${pit.getDate}%02d"

    val dayActorIds = Seq(
      "Manifests (day)" -> s"manifests-$day",
      "Live Feed Arrivals (all terminals)" -> s"live-feed-arrivals--$day",
      "Live Base Feed Arrivals (all terminals)" -> s"live-base-feed-arrivals--$day",
      "Terminal Flights (all terminals)" -> s"terminal-flights--$day",
    )

    val terminalActorIds = airportConfig.terminalsForDate(pit.toLocalDate).toSeq.sortBy(_.toString).flatMap { t =>
      val terminal = t.toString.toLowerCase
      Seq(
        s"Terminal Flights ${t.toString}" -> s"terminal-flights-$terminal-$day",
        s"ACL Feed Arrivals ${t.toString}" -> s"acl-feed-arrivals-$terminal-$day",
        s"Forecast Feed Arrivals ${t.toString}" -> s"forecast-feed-arrivals-$terminal-$day",
        s"Live Feed Arrivals ${t.toString}" -> s"live-feed-arrivals-$terminal-$day",
      )
    }

    (dayActorIds ++ terminalActorIds).distinct
  }

  private case class ActorTraceDiagnostics(persistenceId: String,
                                           messages: Seq[GeneratedMessage],
                                           totalRecovered: Int,
                                           supportedTypeCount: Int,
                                           includedMessageCount: Int,
                                           unhandledTypeCounts: Map[String, Int])

  private def fetchFlightDiffMessagesWithDiagnostics(persistenceId: String,
                                                     pit: SDateLike,
                                                     messages: Int): Future[ActorTraceDiagnostics] = {
    val actor = actorSystem.actorOf(Props(new DebugFlightsActor(persistenceId, Option(pit.millisSinceEpoch))))
    (actor ? DebugStatsQuery)
      .mapTo[DebugStatsResponse]
      .map { response =>
        ActorTraceDiagnostics(
          persistenceId = persistenceId,
          messages = response.messages.take(messages),
          totalRecovered = response.totalRecovered,
          supportedTypeCount = response.supportedTypeCount,
          includedMessageCount = response.includedMessageCount,
          unhandledTypeCounts = response.unhandledTypeCounts,
        )
      }
      .andThen { case _ => actorSystem.stop(actor) }
  }

  private val createdAtRegex: Regex = "(?i)createdAt:\\s*(\\d+)".r
  private val terminalRegex: Regex = "(?i)terminal:\\s*\"([^\"]+)\"".r
  private val statusRegex: Regex = "(?i)status:\\s*\"([^\"]+)\"".r
  private val gateRegex: Regex = "(?i)gate:\\s*\"([^\"]*)\"".r
  private val standRegex: Regex = "(?i)stand:\\s*\"([^\"]*)\"".r
  private val estimatedRegex: Regex = "(?i)estimated:\\s*(\\d+)".r
  private val actualChoxRegex: Regex = "(?i)actualChox:\\s*(\\d+)".r
  private val carrierFromFlightCodeRegex: Regex = """^([A-Z]{2,3})(\d+)""".r
  private val protoCarrierRegex: Regex = "(?i)carrier(?:Code)?:\\s*\"([A-Z0-9]{2,3})\"".r
  private val protoVoyageRegex: Regex = """(?i)(?:voyageNumber|number):\s*(\d+)""".r

  private def firstMatch(regex: Regex, text: String): Option[String] =
    regex.findFirstMatchIn(text).map(_.group(1))

  private def flightCodeParts(flightCodeUpper: String): (Option[String], Option[String]) =
    flightCodeUpper match {
      case carrierFromFlightCodeRegex(carrier, voyage) => Option(carrier) -> Option(voyage)
      case _ => None -> None
    }

  private def scheduledMatchTokens(millis: Long): Seq[String] = {
    val seconds = millis / 1000
    val dayToken = SDate(millis).toISOString.take(10)
    Seq(millis.toString, seconds.toString, dayToken)
  }

  private def feedDiffEventsFromMessage(message: GeneratedMessage,
                                        persistenceId: String,
                                        flightCodeUpper: String,
                                        maybeCarrierCode: Option[String],
                                        maybeVoyageNumber: Option[String],
                                        maybeScheduledMillis: Option[Long],
                                       ): Seq[(Long, String, JsObject)] = {
    val className = message.getClass.getName
    val isFeedDiff = feedDiffMessageClasses.contains(className)

    if (!isFeedDiff) Seq.empty
    else {
      val proto = message.toProtoString
      val protoUpper = proto.toUpperCase

      val containsFlightCode = protoUpper.contains(flightCodeUpper)
      val carrierMatches = maybeCarrierCode.exists { carrier =>
        protoCarrierRegex.findAllMatchIn(protoUpper).exists(_.group(1).toUpperCase == carrier)
      }
      val voyageMatches = maybeVoyageNumber.exists { voyage =>
        protoVoyageRegex.findAllMatchIn(proto).exists(_.group(1) == voyage)
      }
      val containsCarrierAndVoyage = carrierMatches && voyageMatches

      val matchesScheduled = maybeScheduledMillis.forall { millis =>
        scheduledMatchTokens(millis).exists(token => proto.contains(token))
      }

      val matchedBy = Seq(
        Option.when(containsFlightCode)("iata"),
        Option.when(containsCarrierAndVoyage)("carrier+voyage"),
        Option.when(matchesScheduled)("scheduled-token"),
      ).flatten

      if ((containsFlightCode || containsCarrierAndVoyage) && matchesScheduled) {
        val createdAtMillis = firstMatch(createdAtRegex, proto).flatMap(v => Try(v.toLong).toOption).getOrElse(0L)
        val terminal = firstMatch(terminalRegex, proto).getOrElse("")
        val status = firstMatch(statusRegex, proto).getOrElse("")
        val gate = firstMatch(gateRegex, proto).getOrElse("")
        val stand = firstMatch(standRegex, proto).getOrElse("")
        val estimated = firstMatch(estimatedRegex, proto).flatMap(v => Try(v.toLong).toOption).map(v => SDate(v).toISOString)
        val actualChox = firstMatch(actualChoxRegex, proto).flatMap(v => Try(v.toLong).toOption).map(v => SDate(v).toISOString)

        Seq((createdAtMillis, persistenceId, Json.obj(
          "eventType" -> "feed-diff-update",
          "createdAt" -> SDate(createdAtMillis).toISOString,
          "persistenceId" -> persistenceId,
          "flightCode" -> flightCodeUpper,
          "terminal" -> terminal,
          "status" -> status,
          "gate" -> gate,
          "stand" -> stand,
          "estimated" -> estimated,
          "actualChox" -> actualChox,
          "rawMessageClass" -> className,
          "matchedBy" -> matchedBy,
        )))
      }
      else {
        Seq.empty
      }
    }
  }

  private def maybeScheduledMillisFromRequest(request: play.api.mvc.Request[AnyContent]): Option[Long] =
    request.getQueryString("scheduled")
      .flatMap(s => SDate.tryParseString(s).toOption.map(_.millisSinceEpoch))
      .orElse(request.getQueryString("scheduledMillis").flatMap(v => Try(v.toLong).toOption))

  private def scheduledMatches(maybeScheduledMillis: Option[Long], scheduledMillis: Long): Boolean =
    maybeScheduledMillis.forall(_ == scheduledMillis)

  private def flightNumberFromCode(flightCodeUpper: String): String =
    """\d+""".r.findFirstIn(flightCodeUpper).getOrElse("")

  private def optionToString(maybeValue: Option[Any]): String =
    maybeValue.map(_.toString).getOrElse("")

  private def traceEventsFromFlightsDiff(diff: FlightsWithSplitsDiffMessage,
                                         persistenceId: String,
                                         flightCodeUpper: String,
                                         flightNumberOnly: String,
                                         maybeScheduledMillis: Option[Long],
                                        ): Seq[(Long, String, JsObject)] = {
    val createdAt = diff.createdAt.getOrElse(0L)

    val updates: Seq[(Long, String, JsObject)] = diff.updates.collect {
      case update if update.getFlight.getIATA.toUpperCase == flightCodeUpper && scheduledMatches(maybeScheduledMillis, update.getFlight.getScheduled) =>
        (createdAt, persistenceId, Json.obj(
          "eventType" -> "update",
          "createdAt" -> SDate(createdAt).toISOString,
          "persistenceId" -> persistenceId,
          "flightCode" -> update.getFlight.getIATA,
          "terminal" -> update.getFlight.getTerminal,
          "scheduled" -> SDate(update.getFlight.getScheduled).toISOString,
          "status" -> optionToString(update.getFlight.status),
          "gate" -> optionToString(update.getFlight.gate),
          "stand" -> optionToString(update.getFlight.stand),
          "estimated" -> update.getFlight.estimated.map(e => SDate(e).toISOString),
          "actualChox" -> update.getFlight.actualChox.map(c => SDate(c).toISOString),
        ))
    }

    val removals: Seq[(Long, String, JsObject)] = diff.removals.collect {
      case removal if flightNumberOnly.nonEmpty && removal.getNumber.toString == flightNumberOnly && scheduledMatches(maybeScheduledMillis, removal.getScheduled) =>
        (createdAt, persistenceId, Json.obj(
          "eventType" -> "removal",
          "createdAt" -> SDate(createdAt).toISOString,
          "persistenceId" -> persistenceId,
          "flightNumber" -> removal.getNumber,
          "terminal" -> removal.getTerminalName,
          "scheduled" -> SDate(removal.getScheduled).toISOString,
        ))
    }

    updates ++ removals
  }

  private def actorDiagnosticsJson(actorData: Seq[ActorTraceDiagnostics], actorMatchCounts: Map[String, Int]): Seq[JsObject] =
    actorData.map { data =>
      val matchedEvents: Int = actorMatchCounts.getOrElse(data.persistenceId, 0)
      Json.obj(
        "persistenceId" -> data.persistenceId,
        "matchedEvents" -> matchedEvents,
        "decodedDiffMessages" -> data.messages.collect { case _: FlightsWithSplitsDiffMessage => 1 }.size,
        "supportedTypeCount" -> data.supportedTypeCount,
        "totalRecovered" -> data.totalRecovered,
        "includedMessageCount" -> data.includedMessageCount,
        "unhandledTypeCounts" -> data.unhandledTypeCounts,
      )
    }

  def getActorTree: Action[AnyContent] = authByRole(Debug) {
    Action { _ =>
      Ok(ActorTree.get().toString)
    }
  }

  def getMessagesForFlightPersistenceIdAtTime(persistenceId: String, dateString: String, messages: Int): Action[AnyContent] = authByRole(Debug) {
    Action.async { _ =>
      val pit = SDate(dateString)
      val persistenceIds = flightsUiActorIdsForPit(pit)

      if (persistenceId.trim.isEmpty)
        throw new Exception("Invalid actor")

      val actor = actorSystem.actorOf(Props(new DebugFlightsActor(persistenceId, Option(pit.millisSinceEpoch))))

      val actorSelection = persistenceIds.map {
        case (feed, id) =>
          s"<a href='/debug/flights/${encodePathSegment(id)}/${encodePathSegment(pit.toISOString)}/$messages'>$feed</a></br>"
      }.mkString("\n")

      val timeNavigation =
        s"<a href='/debug/flights/$persistenceId/${pit.addDays(-1).toISOString}/$messages'>-1 day</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addHours(-1).toISOString}/$messages'>-1 hour</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addMinutes(-1).toISOString}/$messages'>-1 mins</a> " +
          pit.toISOString +
          s" <a href='/debug/flights/$persistenceId/${pit.addMinutes(1).toISOString}/$messages'>+1 mins</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addHours(1).toISOString}/$messages'>+1 hour</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.addDays(1).toISOString}/$messages'>+1 day</a> "
      val numMessagesNavigation =
        s"<a href='/debug/flights/$persistenceId/${pit.toISOString}/10'>10</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString}/50'>50</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString}/500'>500</a> " +
          s"<a href='/debug/flights/$persistenceId/${pit.toISOString}/1000'>1000</a> "

      val navigation =
        "<h3>Actor Selection</h3>" + actorSelection +
          "<h3>Time</h3>" + timeNavigation +
          "<h3>Messages to show</h3>" + numMessagesNavigation

      (actor ? MessageQuery(messages)).map {
        case m: MessageResponse =>

          val debugHtml = m.messages.collect {
            case f: FlightsWithSplitsDiffMessage =>

              val heading = s"<h3>Created at: ${f.createdAt.map(SDate(_).toISOString).getOrElse("Missing")}</h3>"
              val updates = if (f.updates.nonEmpty) {

                "<table border='1' cellpadding='5' cellspacing='0'><tr>" +
                  s"<td  colspan='14'>Updates: </td>" +
                  "</tr>" +
                  "<tr>" +
                  "<td>Flight code</td>" +
                  "<td>Terminal</td>" +
                  "<td>Scheduled</td>" +
                  "<td>Est</td>" +
                  "<td>Est Chocks</td>" +
                  "<td>Act Chocks</td>" +
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
                      "<td>" + a.getFlight.estimated.map(SDate(_).toISOString).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.estimatedChox.map(SDate(_).toISOString).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.actualChox.map(SDate(_).toISOString).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.pcpTime.map(SDate(_).toISOString).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.status.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.gate.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.stand.getOrElse("-") + "</td>" +
                      "<td>" + s"${if (a.getFlight.totalPax.isEmpty) "-" else a.getFlight.totalPax.map(_.passengers.map(_.actual + ",").getOrElse(""))}" + "</td>" +
                      "<td>" + a.getFlight.totalPax.find(_.feedSource == Option(ApiFeedSource)).flatMap(_.passengers.map(_.actual)).getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.maxPax.getOrElse("-") + "</td>" +
                      "<td>" + a.getFlight.totalPax.find(_.feedSource == Option(ApiFeedSource)).flatMap(_.passengers.map(_.transit)).getOrElse("-") + "</td>" +
                      "</tr>"

                  }).mkString("\n") +
                  "</table>"
              } else "No Updates </br>"
              val removals = if (f.removals.nonEmpty) {

                "<table border='1' cellpadding='5' cellspacing='0'>" +
                  "<tr>" +
                  s"<td colspan='14'>Removals: </td>" +
                  "</tr>" +
                  "<tr>" +
                  s"<td>Scheduled</td>" +
                  s"<td>Terminal</td>" +
                  s"<td>Number</td>" +
                  "</tr>" +
                  f.removals.map(r => {
                    s"<tr><td>${SDate(r.getScheduled).toISOString}</td><td>${r.getTerminalName}</td><td>${r.getNumber}</td></tr>"
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

  def getFlightTraceAtTime(flightCode: String, dateString: String, messages: Int): Action[AnyContent] = authByRole(Debug) {
    Action.async { request =>
      val pit = SDate(dateString)
      val actorIds = traceActorIdsForPit(pit).map(_._2).distinct
      val flightCodeUpper = flightCode.toUpperCase
      val (maybeCarrierCode, maybeVoyageNumber) = flightCodeParts(flightCodeUpper)
      val maybeScheduledMillis = maybeScheduledMillisFromRequest(request)
      val flightNumberOnly = flightNumberFromCode(flightCodeUpper)

      Future.sequence(actorIds.map(id => fetchFlightDiffMessagesWithDiagnostics(id, pit, messages))).map { actorData =>
        val events: Seq[(Long, String, JsObject)] = actorData.flatMap { data =>
          data.messages.flatMap {
            case diff: FlightsWithSplitsDiffMessage =>
              traceEventsFromFlightsDiff(
                diff = diff,
                persistenceId = data.persistenceId,
                flightCodeUpper = flightCodeUpper,
                flightNumberOnly = flightNumberOnly,
                maybeScheduledMillis = maybeScheduledMillis,
              )

            case message: GeneratedMessage =>
              feedDiffEventsFromMessage(
                message = message,
                persistenceId = data.persistenceId,
                flightCodeUpper = flightCodeUpper,
                maybeCarrierCode = maybeCarrierCode,
                maybeVoyageNumber = maybeVoyageNumber,
                maybeScheduledMillis = maybeScheduledMillis,
              )
          }
        }

        val sortedEvents: Seq[JsObject] = events.sortBy(_._1).map(_._3)
        val actorMatchCounts: Map[String, Int] = events
          .map(_._2)
          .groupBy(identity)
          .view
          .mapValues(_.size)
          .toMap
        val actorsWithMatches = actorMatchCounts.keys.toSeq.sorted
        val actorsWithNoMatches = actorIds.diff(actorsWithMatches)

        val actorDiagnostics: Seq[JsObject] = actorDiagnosticsJson(actorData, actorMatchCounts)

        Ok(Json.obj(
          "flightCode" -> flightCodeUpper,
          "pointInTime" -> pit.toISOString,
          "scheduledFilterMillis" -> maybeScheduledMillis,
          "messagesPerActor" -> messages,
          "actorsChecked" -> actorIds,
          "actorsWithMatches" -> actorsWithMatches,
          "actorsWithNoMatches" -> actorsWithNoMatches,
          "eventCount" -> sortedEvents.size,
          "events" -> sortedEvents,
          "actorDiagnostics" -> actorDiagnostics,
        ))
      }
    }
  }
}
