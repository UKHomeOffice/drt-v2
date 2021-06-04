package services.exports

import actors.DateRange
import actors.routing.minutes.MinutesActorLike.MinutesLookup
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.UtcDate
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

object StreamingDesksExport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def colHeadings(deskTitle: String = "req") = List("Pax", "Wait", s"Desks $deskTitle", "Act. wait time", "Act. desks")

  def eGatesHeadings(deskTitle: String) = List("Pax", "Wait", s"Staff $deskTitle", "Act. wait time", "Act. desks")

  def csvHeader(queues: Seq[Queue], deskTitle: String): String = {

    val headingsLine1 = "Date,," + queueHeadings(queues) + ",Misc,Moves,PCP Staff,PCP Staff"
    val headingsLine2 = ",Start," + queues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings(deskTitle) else colHeadings(deskTitle)
    }).mkString(",") +
      s",Staff req,Staff movements,Avail,Req"

    headingsLine1 + "\n" + headingsLine2 + "\n"
  }

  def queueHeadings(queues: Seq[Queue]): String = queues.map(queue => Queues.displayName(queue))
    .flatMap(qn => List.fill(colHeadings().length)(Queues.exportQueueDisplayNames.getOrElse(Queue(qn), qn))).mkString(",")

  def deskRecsToCSVStreamWithHeaders(
                                      start: SDateLike,
                                      end: SDateLike,
                                      terminal: Terminal,
                                      exportQueuesInOrder: List[Queue],
                                      crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                      staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                      maybePit: Option[MillisSinceEpoch] = None
                                    )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(
      start,
      end,
      terminal,
      exportQueuesInOrder,
      crunchMinuteLookup,
      staffMinuteLookup,
      deskRecsCsv,
      maybePit)
      .prepend(Source(List(csvHeader(exportQueuesInOrder, "req"))))


  def deploymentsToCSVStreamWithHeaders(
                                         start: SDateLike,
                                         end: SDateLike,
                                         terminal: Terminal,
                                         exportQueuesInOrder: List[Queue],
                                         crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                         staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                         maybePit: Option[MillisSinceEpoch] = None
                                       )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(start, end, terminal, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup, deploymentsCsv, maybePit)
      .prepend(Source(List(csvHeader(exportQueuesInOrder, "dep"))))


  def exportDesksToCSVStream(
                              start: SDateLike,
                              end: SDateLike,
                              terminal: Terminal,
                              exportQueuesInOrder: List[Queue],
                              crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                              staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                              deskExportFn: CrunchMinute => String,
                              maybePit: Option[MillisSinceEpoch] = None
                            )(implicit ec: ExecutionContext): Source[String, NotUsed] = {

    DateRange.utcDateRangeSource(start, end)
      .mapAsync(1)(crunchUtcDate => {

        val futureMaybeCM: Future[Option[CrunchApi.MinutesContainer[CrunchMinute, TQM]]] = crunchMinuteLookup((terminal, crunchUtcDate), maybePit)
        val futureMaybeSM: Future[Option[CrunchApi.MinutesContainer[StaffMinute, TM]]] = staffMinuteLookup((terminal, crunchUtcDate), maybePit)
        for {
          maybeCMs <- futureMaybeCM
          maybeSMs <- futureMaybeSM
        } yield {
          minutesToCsv(
            terminal,
            exportQueuesInOrder,
            crunchUtcDate,
            start,
            end,
            maybeCMs.map(_.minutes.map(_.toMinute)).getOrElse(List()),
            maybeSMs.map(_.minutes.map(_.toMinute)).getOrElse(List()),
            deskExportFn
          )
        }
      })
  }

  type MaybeCrunchMinutes = Option[CrunchApi.MinutesContainer[CrunchMinute, TQM]]
  type MaybeStaffMinutes = Option[CrunchApi.MinutesContainer[StaffMinute, TM]]
  type MinutesTuple = (MaybeCrunchMinutes, MaybeStaffMinutes)

  def minutesToCsv(terminal: Terminal,
                   exportQueuesInOrder: List[Queue],
                   utcDate: UtcDate,
                   start: SDateLike,
                   end: SDateLike,
                   crunchMinutes: Iterable[CrunchMinute],
                   staffMinutes: Iterable[StaffMinute],
                   deskExportFn: CrunchMinute => String,
                  ): String = {
    val portState = PortState(
      List(),
      crunchMinutes,
      staffMinutes
    )

    val terminalCrunchMinutes = portState
      .crunchSummary(SDate(utcDate), 24 * 4, 15, terminal, exportQueuesInOrder)
    val terminalCrunchMinutesWithinRange: SortedMap[MillisSinceEpoch, Map[Queue, CrunchMinute]] = terminalCrunchMinutes.filter {
      case (millis, _) => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch
    }

    val terminalStaffMinutes = portState
      .staffSummary(SDate(utcDate), 24 * 4, 15, terminal)
    val terminalStaffMinutesWithinRange: Map[MillisSinceEpoch, StaffMinute] = terminalStaffMinutes.filter {
      case (millis, _) => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch
    }

    terminalCrunchMinutesWithinRange.map {
      case (minute, qcm) =>
        val qcms: immutable.Seq[CrunchMinute] = exportQueuesInOrder.map(q => qcm.get(q)).collect {
          case Some(qcm) => qcm
        }
        val qsCsv: String = qcms.map(deskExportFn).mkString(",")
        val staffMinutesCsv = terminalStaffMinutesWithinRange.get(minute) match {
          case Some(sm) =>
            s"${sm.fixedPoints},${sm.movements},${sm.shifts}"
          case _ => "Missing staffing data for this period,,"
        }
        val total = qcms.map(_.deskRec).sum
        val localMinute = SDate(minute, Crunch.europeLondonTimeZone)
        val misc = terminalStaffMinutesWithinRange.get(minute).map(_.fixedPoints).getOrElse(0)
        s"${localMinute.toISODateOnly},${localMinute.prettyTime()},$qsCsv,$staffMinutesCsv,${total + misc}\n"

    }.mkString
  }

  def deskRecsCsv(cm: CrunchMinute): String =
    s"${Math.round(cm.paxLoad)},${cm.waitTime},${cm.deskRec},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"

  def deploymentsCsv(cm: CrunchMinute): String =
    s"${Math.round(cm.paxLoad)},${cm.deployedWait.getOrElse("")},${cm.deployedDesks.getOrElse(0)},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"
}
