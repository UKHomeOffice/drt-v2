package services.exports.summaries.flights

import java.util.UUID

import actors.pointInTime.ArrivalsReadActor
import actors.{ArrivalsState, GetState}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, FeedSource, SDateLike, UniqueArrival}
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

  def flightsForDay(day: MillisSinceEpoch, fs: FeedSource, persistenceId: String): Future[Option[String]] = {
    val exportDay = SDate(day)

    val feedActor: ActorRef = system
      .actorOf(
        ArrivalsReadActor.props(SDate(day).getLocalNextMidnight,
          persistenceId,
          fs
        ),
        name = s"arrival-read-$fs-${UUID.randomUUID()}"
      )

    val askableActorRef: AskableActorRef = feedActor

    askableActorRef
      .ask(GetState)(Timeout(5 seconds))
      .map {
        case ArrivalsState(arrivals: mutable.Map[UniqueArrival, Arrival], _, _) =>
          feedActor ! PoisonPill
          system.log.info(s"Exporting $fs arrivals for ${exportDay.toISODateOnly}")
          val csvData = arrivals
            .values
            .filter(_.Actual match {
              case Some(act) => SDate(act).toISODateOnly == exportDay.toISODateOnly
              case _ => false
            })
            .map(a =>
              TerminalFlightsSummary.arrivalAsRawCsvValuesWithTransfer(
                a,
                Exports.millisToLocalIsoDateOnly,
                Exports.millisToLocalHoursAndMinutes
              )
            )
          Option(asCSV(csvData))

        case _ =>
          feedActor ! PoisonPill
          None
      }
  }

  def headingsSource = Source(List(Option(TerminalFlightsSummary.rawArrivalHeadingsWithTransfer + lineEnding)))

  def flightsDataSource(startDate: SDateLike, numberOfDays: Int, fs: FeedSource, persistenceId: String) =
    Source(0 until numberOfDays)
      .mapAsync(1)(day => {
        flightsForDay(startDate.addDays(day).millisSinceEpoch, fs, persistenceId)
      }).prepend(headingsSource)


}
