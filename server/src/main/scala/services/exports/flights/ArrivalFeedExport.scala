package services.exports.flights

import actors.persistent.arrivals.ArrivalsReadActor
import actors.persistent.staffing.GetState
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import services.exports.Exports
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class ArrivalFeedExport()(implicit system: ActorSystem, executionContext: ExecutionContext) {

  val lineEnding = "\n"

  def asCSV(csvData: Iterable[List[Any]]): String =
    if (csvData.nonEmpty)
      csvData.map(_.mkString(",")).mkString(lineEnding) + lineEnding
    else lineEnding

  def flightsForDay(day: MillisSinceEpoch, terminal: Terminal, fs: FeedSource, persistenceId: String): Future[Option[String]] = {
    val exportDay = SDate(day)

    val snapshotDate = SDate(day).getLocalNextMidnight.addDays(1).addHours(12)

    val feedActor: ActorRef = system
      .actorOf(
        ArrivalsReadActor.props(snapshotDate, persistenceId, fs), name = s"arrival-read-$fs-${UUID.randomUUID()}"
      )

    feedActor
      .ask(GetState)(Timeout(60 seconds))
      .map {
        case ArrivalsState(arrivals, _, _) =>
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

  def arrivalsToCsvRows(terminal: Terminal,
                        arrivals: SortedMap[UniqueArrival, Arrival],
                        exportDay: SDateLike
                       ): Iterable[List[String]] = {

    val arrivalsForDay = arrivals
      .values
      .filter(a => a.Terminal == terminal && !a.Origin.isDomestic)
      .filter(a => isScheduledForExportDay(a, exportDay))

    val csvData = arrivalsForDay
      .map(a =>
        ArrivalToCsv.arrivalWithTransferToCsvFields(
          a,
          Exports.millisToLocalIsoDateOnly,
          Exports.millisToLocalDateTimeString
        )
      )
    csvData
  }

  def isScheduledForExportDay(arrival: Arrival, day: SDateLike): Boolean =
    arrival.Scheduled > day.getLocalLastMidnight.millisSinceEpoch && arrival.Scheduled < day.getLocalNextMidnight.millisSinceEpoch


  def headingsSource: Source[Option[String], NotUsed] = Source(
    List(Option(ArrivalToCsv.arrivalHeadingsWithTransfer + lineEnding))
  )

  def flightsDataSource(startDate: SDateLike,
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
