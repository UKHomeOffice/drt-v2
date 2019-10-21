package controllers.application

import actors._
import actors.pointInTime.CrunchStateReadActor
import akka.NotUsed
import akka.actor.Props
import akka.pattern._
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import controllers.Application
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import drt.users.KeyCloakGroups
import play.api.http.{HeaderNames, HttpChunk, HttpEntity, Writeable}
import play.api.mvc._
import services.graphstages.Crunch
import services.graphstages.Crunch._
import services.{CSVData, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try


trait WithExports {
  self: Application =>

  def exportUsers(): Action[AnyContent] = authByRole(ManageUsers) {
    Action.async { request =>
      val client = keyCloakClient(request.headers)
      client
        .getGroups
        .flatMap(groupList => KeyCloakGroups(groupList, client).usersWithGroupsCsvContent)
        .map(csvContent => Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=users-with-groups.csv")),
          HttpEntity.Strict(ByteString(csvContent), Option("application/csv"))
        ))
    }
  }

  def exportDesksAndQueuesAtPointInTimeCSV(pointInTime: String,
                                           terminalName: TerminalName,
                                           startHour: Int,
                                           endHour: Int
                                          ): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action.async {
        timedEndPoint(s"Export desks & queues", Option(s"$terminalName @ ${SDate(pointInTime.toLong).toISOString()}")) {
          val portCode = airportConfig.portCode
          val pit = SDate(pointInTime.toLong)

          val fileName = f"$portCode-$terminalName-desks-and-queues-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
            f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

          val startMillis = dayStartMillisWithHourOffset(startHour, pit)
          val endMillis = dayStartMillisWithHourOffset(endHour, pit)
          val portStateForPointInTime = loadBestPortStateForPointInTime(pit.millisSinceEpoch, terminalName, startMillis, endMillis)
          exportDesksToCSV(terminalName, pit, startHour, endHour, portStateForPointInTime, includeHeader = true).map {
            case Some(csvData) =>
              val header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv"))
              val entity = HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
              Result(header, entity)
            case None =>
              NotFound("Could not find desks and queues for this date.")
          }
        }
      }
    }

  def exportForecastWeekToCSV(startDay: String, terminalName: TerminalName): Action[AnyContent] = authByRole(ForecastView) {
    Action.async {
      timedEndPoint(s"Export planning", Option(s"$terminalName")) {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay.toLong, 180)

        val portStateFuture = ctrl.forecastCrunchStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminalName)
        )(new Timeout(30 seconds))

        val portCode = airportConfig.portCode

        val fileName = f"$portCode-$terminalName-forecast-export-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
        portStateFuture.map {
          case Some(portState: PortState) =>
            val fp = Forecast.forecastPeriod(airportConfig, terminalName, startOfForecast, endOfForecast, portState)
            val csvData = CSVData.forecastPeriodToCsv(fp)
            Result(
              ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
            )

          case None =>
            log.error(s"Forecast CSV Export: Missing planning data for ${startOfForecast.ddMMyyString} for Terminal $terminalName")
            NotFound(s"Sorry, no planning summary available for week starting ${startOfForecast.ddMMyyString}")
        }
      }
    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String, terminalName: TerminalName): Action[AnyContent] = authByRole(ForecastView) {
    Action.async {
      timedEndPoint(s"Export planning headlines", Option(s"$terminalName")) {
        val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay.toLong))
        val endOfForecast = startOfWeekMidnight.addDays(180)
        val now = SDate.now()

        val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
          log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
          getLocalNextMidnight(now)
        } else startOfWeekMidnight

        val portStateFuture = ctrl.forecastCrunchStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminalName)
        )(new Timeout(30 seconds))

        val fileName = f"${airportConfig.portCode}-$terminalName-forecast-export-headlines-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
        portStateFuture.map {
          case Some(portState: PortState) =>
            val hf: ForecastHeadlineFigures = Forecast.headlineFigures(startOfForecast, endOfForecast, terminalName, portState, airportConfig.queues(terminalName).toList)
            val csvData = CSVData.forecastHeadlineToCSV(hf, airportConfig.exportQueueOrder)
            Result(
              ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv")
              )
            )

          case None =>
            log.error(s"Missing headline data for ${startOfWeekMidnight.ddMMyyString} for Terminal $terminalName")
            NotFound(s"Sorry, no headlines available for week starting ${startOfWeekMidnight.ddMMyyString}")
        }
      }
    }

  }

  def exportApi(day: Int, month: Int, year: Int, terminalName: TerminalName): Action[AnyContent] = authByRole(ApiViewPortCsv) {
    Action.async { _ =>
      val startHour = 0
      val endHour = 24
      val dateOption = Try(SDate(year, month, day, 0, 0)).toOption
      val terminalNameOption = airportConfig.terminalNames.find(name => name == terminalName)
      val resultOption = for {
        date <- dateOption
        terminalName <- terminalNameOption
      } yield {
        val pit = date.millisSinceEpoch
        val startMillis = dayStartMillisWithHourOffset(startHour, date)
        val endMillis = dayStartMillisWithHourOffset(endHour, date)
        val portStateForPointInTime = loadBestPortStateForPointInTime(pit, terminalName, startMillis, endMillis)
        val fileName = f"export-splits-$portCode-$terminalName-${date.getFullYear()}-${date.getMonth()}-${date.getDate()}"
        flightsForCSVExportWithinRange(terminalName, date, startHour = startHour, endHour = endHour, portStateForPointInTime).map {
          case Some(csvFlights) =>
            val csvData = CSVData.flightsWithSplitsWithAPIActualsToCSVWithHeadings(csvFlights)
            Result(
              ResponseHeader(OK, Map(
                CONTENT_LENGTH -> csvData.length.toString,
                CONTENT_TYPE -> "text/csv",
                CONTENT_DISPOSITION -> s"attachment; filename=$fileName.csv",
                CACHE_CONTROL -> "no-cache")
              ),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
            )
          case None => NotFound("No data for this date")
        }
      }
      resultOption.getOrElse(
        Future(BadRequest("Invalid terminal name or date"))
      )
    }
  }

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String,
                                              terminalName: TerminalName,
                                              startHour: Int,
                                              endHour: Int): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      implicit request =>
        timedEndPoint(s"Export flights with splits", Option(s"$terminalName @ ${SDate(pointInTime.toLong).toISOString()}")) {
          val pit = SDate(pointInTime.toLong)

          val portCode = airportConfig.portCode
          val fileName = f"$portCode-$terminalName-arrivals-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
            f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

          val startMillis = dayStartMillisWithHourOffset(startHour, pit)
          val endMillis = dayStartMillisWithHourOffset(endHour, pit)
          val portStateForPointInTime = loadBestPortStateForPointInTime(pit.millisSinceEpoch, terminalName, startMillis, endMillis)
          flightsForCSVExportWithinRange(terminalName, pit, startHour, endHour, portStateForPointInTime).map {
            case Some(csvFlights) =>
              val csvData = if (ctrl.getRoles(config, request.headers, request.session).contains(ApiView)) {
                log.info(s"Sending Flights CSV with API data")
                CSVData.flightsWithSplitsWithAPIActualsToCSVWithHeadings(csvFlights)
              }
              else {
                log.info(s"Sending Flights CSV with no API data")
                CSVData.flightsWithSplitsToCSVWithHeadings(csvFlights)
              }
              Result(
                ResponseHeader(200, Map(
                  "Content-Disposition" -> s"attachment; filename=$fileName.csv",
                  HeaderNames.CACHE_CONTROL -> "no-cache")
                ),
                HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
              )
            case None => NotFound("No data for this date")
          }
        }
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(start: String,
                                                  end: String,
                                                  terminalName: TerminalName): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    log.info(s"Export flights with splits for terminal $terminalName between ${SDate(start.toLong).toISOString()} & ${SDate(end.toLong).toISOString()}")
    val func = (day: SDateLike, ps: Future[Either[PortStateError, Option[PortState]]], includeHeader: Boolean) => flightsForCSVExportWithinRange(
      terminalName = terminalName,
      pit = day,
      startHour = 0,
      endHour = 24,
      portStateFuture = ps
    ).map {
      case Some(fs) if includeHeader => Option(CSVData.flightsWithSplitsToCSVWithHeadings(fs))
      case Some(fs) if !includeHeader => Option(CSVData.flightsWithSplitsToCSV(fs))
      case None =>
        log.error(s"Missing a day of flights")
        None
    }
    exportTerminalDateRangeToCsv(start, end, terminalName, filePrefix = "arrivals", csvFunc = func)

  }

  def exportDesksAndQueuesBetweenTimeStampsCSV(start: String,
                                               end: String,
                                               terminalName: TerminalName): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    log.info(s"Export desks & queues for terminal $terminalName between ${SDate(start.toLong).toISOString()} & ${SDate(end.toLong).toISOString()}")
    val func = (day: SDateLike, ps: Future[Either[PortStateError, Option[PortState]]], includeHeader: Boolean) => exportDesksToCSV(
      terminalName = terminalName,
      pointInTime = day,
      startHour = 0,
      endHour = 24,
      portStateFuture = ps,
      includeHeader
    )
    exportTerminalDateRangeToCsv(start, end, terminalName, filePrefix = "desks-and-queues", csvFunc = func)

  }

  private def exportDesksToCSV(terminalName: TerminalName,
                               pointInTime: SDateLike,
                               startHour: Int,
                               endHour: Int,
                               portStateFuture: Future[Either[PortStateError, Option[PortState]]],
                               includeHeader: Boolean
                              ): Future[Option[String]] = {

    val startDateTime = getLocalLastMidnight(pointInTime).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pointInTime).addHours(endHour)
    val localTime = SDate(pointInTime, europeLondonTimeZone)

    portStateFuture.map {
      case Right(Some(ps: PortState)) =>
        val wps = ps.windowWithTerminalFilter(startDateTime, endDateTime, airportConfig.queues.filterKeys(_ == terminalName))
        wps.crunchMinutes.foreach {
          case (_, cm) => if (cm.deskRec != 0) println(s"${SDate(cm.minute).toISOString()}: ${cm.deskRec} desks")
        }
        val dataLines = CSVData.terminalMinutesToCsvData(wps.crunchMinutes, wps.staffMinutes, airportConfig.nonTransferQueues(terminalName), startDateTime, endDateTime, 15)
        val fullData = if (includeHeader) {
          val headerLines = CSVData.terminalCrunchMinutesToCsvDataHeadings(airportConfig.queues(terminalName))
          headerLines + CSVData.lineEnding + dataLines
        } else dataLines
        Option(fullData)

      case unexpected =>
        log.error(s"Exports: Got the wrong thing $unexpected for Point In time: ${
          localTime.toISOString()
        }")

        None
    }
  }

  private def exportTerminalDateRangeToCsv(start: String,
                                           end: String,
                                           terminalName: TerminalName,
                                           filePrefix: String,
                                           csvFunc: (SDateLike, Future[Either[PortStateError, Option[PortState]]], Boolean) => Future[Option[String]]
                                          ): Action[AnyContent] = Action {
    val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
    val endPit = SDate(end.toLong, europeLondonTimeZone)

    val portCode = airportConfig.portCode
    val fileName = makeFileName(filePrefix, terminalName, startPit, endPit, portCode)

    val dayRangeMillis = startPit.millisSinceEpoch to endPit.millisSinceEpoch by Crunch.oneDayMillis
    val daysMillisSource: Source[String, NotUsed] = Source(dayRangeMillis)
      .mapAsync(parallelism = 1) {
        millis =>
          val day = SDate(millis)
          val start = dayStartWithHourOffset(0, day)
          val end = start.addHours(24)
          val includeHeader = millis == startPit.millisSinceEpoch
          val psForDay = loadBestPortStateForPointInTime(millis, terminalName, start.millisSinceEpoch, end.millisSinceEpoch)
          timedEndPoint(s"Export multi-day", Option(s"$terminalName ${startPit.toISOString()} -> ${endPit.toISOString()} (day ${day.toISOString()})")) {
            csvFunc(day, psForDay, includeHeader)
          }
      }
      .collect {
        case Some(dayData) => dayData + CSVData.lineEnding
      }

    implicit val writeable: Writeable[String] = Writeable((str: String) => ByteString.fromString(str), Option("application/csv"))

    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
      body = HttpEntity.Chunked(daysMillisSource.map(c => HttpChunk.Chunk(writeable.transform(c))), writeable.contentType)
    )
  }

  private def flightsForCSVExportWithinRange(terminalName: TerminalName,
                                             pit: SDateLike,
                                             startHour: Int,
                                             endHour: Int,
                                             portStateFuture: Future[Either[PortStateError, Option[PortState]]]
                                            ): Future[Option[List[ApiFlightWithSplits]]] = {

    val startDateTime = getLocalLastMidnight(pit).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pit).addHours(endHour)
    val isInRange = isInRangeOnDay(startDateTime, endDateTime) _

    portStateFuture.map {
      case Right(Some(PortState(fs, _, _))) =>

        val flightsForTerminalInRange = fs.values
          .filter(_.apiFlight.Terminal == terminalName)
          .filter(_.apiFlight.PcpTime.isDefined)
          .filter(f => isInRange(SDate(f.apiFlight.PcpTime.getOrElse(0L), europeLondonTimeZone)))
          .toList

        Option(flightsForTerminalInRange)
      case unexpected =>
        log.error(s"got the wrong thing extracting flights from PortState (terminal: $terminalName, millis: $pit," +
          s" start hour: $startHour, endHour: $endHour): Error: $unexpected")
        None
    }
  }

  private def makeFileName(subject: String,
                           terminalName: TerminalName,
                           startPit: SDateLike,
                           endPit: SDateLike,
                           portCode: String): String = {
    f"$portCode-$terminalName-$subject-" +
      f"${startPit.getFullYear()}-${startPit.getMonth()}%02d-${startPit.getDate()}-to-" +
      f"${endPit.getFullYear()}-${endPit.getMonth()}%02d-${endPit.getDate()}"
  }

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay))
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)
    val now = SDate.now()

    val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) getLocalNextMidnight(now) else startOfWeekMidnight

    (startOfForecast, endOfForecast)
  }

  private def loadBestPortStateForPointInTime(day: MillisSinceEpoch, terminalName: TerminalName, startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): Future[Either[PortStateError, Option[PortState]]] =
    if (isHistoricDate(day)) {
      portStateForEndOfDay(day, terminalName)
    } else if (day <= getLocalNextMidnight(SDate.now()).millisSinceEpoch) {
      ctrl.liveCrunchStateActor.ask(GetState).map {
        case Some(ps: PortState) => Right(Option(ps))
        case _ => Right(None)
      }
    } else {
      portStateForDayInForecast(day)
    }

  private def portStateForDayInForecast(day: MillisSinceEpoch): Future[Either[PortStateError, Option[PortState]]] = {
    val firstMinute = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val lastMinute = SDate(firstMinute).addHours(airportConfig.dayLengthHours).millisSinceEpoch

    val portStateFuture = ctrl.forecastCrunchStateActor.ask(GetPortState(firstMinute, lastMinute))(new Timeout(30 seconds))

    portStateFuture.map {
      case Some(ps: PortState) => Right(Option(ps))
      case _ => Right(None)
    } recover {
      case t =>
        log.warning(s"Didn't get a PortState: $t")
        Left(PortStateError(t.getMessage))
    }
  }

  private def isHistoricDate(day: MillisSinceEpoch): Boolean = day < getLocalLastMidnight(SDate.now()).millisSinceEpoch

  private def portStateForEndOfDay(day: MillisSinceEpoch, terminalName: TerminalName): Future[Either[PortStateError, Option[PortState]]] = {
    val relativeLastMidnight = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val startMillis = relativeLastMidnight
    val endMillis = relativeLastMidnight + oneHourMillis * airportConfig.dayLengthHours
    val pointInTime = startMillis + oneDayMillis + oneHourMillis * 3

    portStatePeriodAtPointInTime(startMillis, endMillis, pointInTime, terminalName)
  }

  private def portStatePeriodAtPointInTime(startMillis: MillisSinceEpoch,
                                           endMillis: MillisSinceEpoch,
                                           pointInTime: MillisSinceEpoch,
                                           terminalName: TerminalName): Future[Either[PortStateError, Option[PortState]]] = {
    val stateQuery = GetPortStateForTerminal(startMillis, endMillis, terminalName)
    val terminalsAndQueues = airportConfig.queues.filterKeys(_ == terminalName)
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], airportConfig.portStateSnapshotInterval, SDate(pointInTime), DrtStaticParameters.expireAfterMillis, terminalsAndQueues, startMillis, endMillis), stateQuery)
    val portCrunchResult = cacheActorRef.ask(query)(new Timeout(15 seconds))


    portCrunchResult.map {
      case Some(ps: PortState) =>
        log.info(s"Got point-in-time PortState for ${
          SDate(pointInTime).toISOString()
        }")
        Right(Option(ps))
      case _ => Right(None)
    }.recover {
      case t =>
        log.warning(s"Didn't get a point-in-time PortState: $t")
        Left(PortStateError(t.getMessage))
    }
  }


  private def dayStartMillisWithHourOffset(startHour: Int, pit: SDateLike): MillisSinceEpoch = dayStartWithHourOffset(startHour, pit).millisSinceEpoch

  private def dayStartWithHourOffset(startHour: Int, pit: SDateLike): SDateLike =
    getLocalLastMidnight(pit).addHours(startHour)
}

object Forecast {
  def headlineFigures(startOfForecast: SDateLike, endOfForecast: SDateLike, terminal: TerminalName, portState: PortState, queues: List[QueueName]): ForecastHeadlineFigures = {
    val dayMillis = 60 * 60 * 24 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / dayMillis
    val crunchSummaryDaily = portState.crunchSummary(startOfForecast, periods, 1440, terminal, queues)

    val figures = for {
      (dayMillis, queueMinutes) <- crunchSummaryDaily
      (queue, queueMinute) <- queueMinutes
    } yield {
      QueueHeadline(dayMillis, queue, queueMinute.paxLoad.toInt, queueMinute.workLoad.toInt)
    }
    ForecastHeadlineFigures(figures.toSeq)
  }

  def forecastPeriod(airportConfig: AirportConfig, terminal: TerminalName, startOfForecast: SDateLike, endOfForecast: SDateLike, portState: PortState): ForecastPeriod = {
    val fifteenMinuteMillis = 15 * 60 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / fifteenMinuteMillis
    val staffSummary = portState.staffSummary(startOfForecast, periods, 15, terminal)
    val crunchSummary15Mins = portState.crunchSummary(startOfForecast, periods, 15, terminal, airportConfig.nonTransferQueues(terminal).toList)
    val timeSlotsByDay = Forecast.rollUpForWeek(crunchSummary15Mins, staffSummary)
    ForecastPeriod(timeSlotsByDay)
  }

  def rollUpForWeek(crunchSummary: Map[MillisSinceEpoch, Map[QueueName, CrunchMinute]],
                    staffSummary: Map[MillisSinceEpoch, StaffMinute]
                   ): Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] =
    crunchSummary
      .map { case (millis, cms) =>
        val (available, fixedPoints) = staffSummary.get(millis).map(sm => (sm.shifts, sm.fixedPoints)).getOrElse((0, 0))
        val deskStaff = if (cms.nonEmpty) cms.values.map(_.deskRec).sum else 0
        ForecastTimeSlot(millis, available, fixedPoints + deskStaff)
      }
      .groupBy(forecastTimeSlot => getLocalLastMidnight(SDate(forecastTimeSlot.startMillis)).millisSinceEpoch)
      .mapValues(_.toSeq)
}
