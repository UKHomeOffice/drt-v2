package services.exports.summaries.flights

import java.util.UUID

import actors.pointInTime.ArrivalsReadActor
import actors.{ArrivalsState, GetState, Ports}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{FeedSource, SDateLike, UniqueArrival}
import services.SDate
import services.exports.Exports

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class ArrivalFeedExport()(implicit system: ActorSystem, executionContext: ExecutionContext) {

  val lineEnding = "\n"

  def asCSV(csvData: Iterable[List[Any]]): String =
    if (csvData.nonEmpty)
      csvData.map(_.mkString(",")).mkString(lineEnding) + lineEnding
    else lineEnding

  def flightsForDay(day: MillisSinceEpoch, terminal: Terminal, fs: FeedSource, persistenceId: String): Future[Option[String]] = {
    val exportDay = SDate(day)

    val snapshotDate = SDate(day).getLocalNextMidnight.addDays(2)

    val feedActor: ActorRef = system
      .actorOf(
        ArrivalsReadActor.props(snapshotDate, persistenceId, fs), name = s"arrival-read-$fs-${UUID.randomUUID()}"
      )

    feedActor
      .ask(GetState)(Timeout(60 seconds))
      .map {
        case ArrivalsState(arrivals: mutable.Map[UniqueArrival, Arrival], _, _) =>
          feedActor ! PoisonPill
          system.log.info(s"Exporting $fs arrivals for ${exportDay.toISODateOnly}")
          val csvData: Iterable[List[String]] = arrivalsToCsvRows(terminal, arrivals, exportDay)
          Option(asCSV(csvData))

        case _ =>
          system.log.error(s"No flights found for ${SDate(day).toISODateOnly} in $fs")
          feedActor ! PoisonPill
          None
      }
  }.recover {
    case e: Throwable =>
      system.log.error(e, s"Unable to recover flights for ${SDate(day).toISODateOnly} in $fs")
      None
  }

  def arrivalsToCsvRows(
                         terminal: Terminal,
                         arrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         exportDay: SDateLike
                       ): Iterable[List[String]] = {

    val arrivalsForDay = arrivals
      .values
      .filter(a => a.Terminal == terminal && !Ports.domesticPorts.contains(a.Origin))
      .filter(a => pcpTimeIsOnDay(a, exportDay))

    val csvData = arrivalsForDay
      .map(a =>
        TerminalFlightsSummary.arrivalAsRawCsvValuesWithTransfer(
          a,
          Exports.millisToLocalIsoDateOnly,
          Exports.millisToLocalHoursAndMinutes
        )
      )
    csvData
  }

  def pcpTimeIsOnDay(arrival: Arrival, day: SDateLike): Boolean = arrival.PcpTime
    .exists(
      t => t > day.getLocalLastMidnight.millisSinceEpoch && t < day.getLocalNextMidnight.millisSinceEpoch
    )

  def headingsSource: Source[Option[String], NotUsed] = Source(
    List(Option(TerminalFlightsSummary.rawArrivalHeadingsWithTransfer + lineEnding))
  )

  def flightsDataSource(
                         startDate: SDateLike,
                         numberOfDays: Int,
                         terminal: Terminal,
                         fs: FeedSource,
                         persistenceId: String
                       ): Source[Option[String], NotUsed] =
    Source(0 until numberOfDays)
      .mapAsync(1)(day => {
        flightsForDay(startDate.addDays(day).millisSinceEpoch, terminal, fs, persistenceId)
      }).prepend(headingsSource)
}
