package services.exports

import actors.minutes.MinutesActorLike.MinutesLookup
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.exports.summaries.queues.TerminalQueuesSummary.{colHeadings, eGatesHeadings}
import services.graphstages.Crunch

import scala.collection.immutable
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

  def queueHeadings(queues: Seq[Queue]): String = queues.map(queue => Queues.queueDisplayNames.getOrElse(queue, queue.toString))
    .flatMap(qn => List.fill(colHeadings().length)(Queues.exportQueueDisplayNames.getOrElse(Queue(qn), qn))).mkString(",")

  def deskRecsToCSVStreamWithHeaders(
                                   dates: Source[UtcDate, NotUsed],
                                   terminal: Terminal,
                                   exportQueuesInOrder: List[Queue],
                                   crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                   staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                   maybePit: Option[MillisSinceEpoch] = None
                                 )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    deskRecsToCSVStream(dates, terminal, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup, maybePit)
      .prepend(Source(List(csvHeader(exportQueuesInOrder, "req"))))

  def deskRecsToCSVStream(
                           dates: Source[UtcDate, NotUsed],
                           terminal: Terminal,
                           exportQueuesInOrder: List[Queue],
                           crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                           staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                           maybePit: Option[MillisSinceEpoch] = None
                         )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(
      dates,
      terminal,
      exportQueuesInOrder,
      crunchMinuteLookup,
      staffMinuteLookup,
      deskRecsCsv,
      maybePit)

  def deploymentsToCSVStreamWithHeaders(
                                   dates: Source[UtcDate, NotUsed],
                                   terminal: Terminal,
                                   exportQueuesInOrder: List[Queue],
                                   crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                   staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                   maybePit: Option[MillisSinceEpoch] = None
                                 )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    deploymentsToCSVStream(dates, terminal, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup, maybePit)
      .prepend(Source(List(csvHeader(exportQueuesInOrder, "dep"))))

  def deploymentsToCSVStream(
                           dates: Source[UtcDate, NotUsed],
                           terminal: Terminal,
                           exportQueuesInOrder: List[Queue],
                           crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                           staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                           maybePit: Option[MillisSinceEpoch] = None
                         )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(
      dates,
      terminal,
      exportQueuesInOrder,
      crunchMinuteLookup,
      staffMinuteLookup,
      deploymentsCsv,
      maybePit)


  def exportDesksToCSVStream(
                        dates: Source[UtcDate, NotUsed],
                        terminal: Terminal,
                        exportQueuesInOrder: List[Queue],
                        crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                        staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                        deskExportFn: CrunchMinute => String,
                        maybePit: Option[MillisSinceEpoch] = None
                      )(implicit ec: ExecutionContext): Source[String, NotUsed] = {

    dates.mapAsync(1)(d => {
      val futureMaybeCM: Future[Option[CrunchApi.MinutesContainer[CrunchMinute, TQM]]] = crunchMinuteLookup(terminal, SDate(d), maybePit)
      val futureMaybeSM: Future[Option[CrunchApi.MinutesContainer[StaffMinute, TM]]] = staffMinuteLookup(terminal, SDate(d), maybePit)
      for {
        maybeCMs <- futureMaybeCM
        maybeSMs <- futureMaybeSM
      } yield {

        val portState = PortState(
          List(),
          maybeCMs.map(_.minutes.map(_.toMinute)).getOrElse(List()),
          maybeSMs.map(_.minutes.map(_.toMinute)).getOrElse(List())
        )
        val terminalCrunchMinutes: immutable.SortedMap[MillisSinceEpoch, Map[Queue, CrunchMinute]] = portState
          .crunchSummary(SDate(d), 24 * 4, 15, terminal, exportQueuesInOrder)
        val terminalStaffMinutes: Map[MillisSinceEpoch, StaffMinute] = portState
          .staffSummary(SDate(d), 24 * 4, 15, terminal)

        terminalCrunchMinutes.map {
          case (minute, qcm) =>
            val qcms: immutable.Seq[CrunchMinute] = exportQueuesInOrder.map(q => qcm.get(q)).collect {
              case Some(qcm) => qcm
            }
            val qsCsv: String = qcms.map(deskExportFn).mkString(",")
            val staffMinutesCsv = terminalStaffMinutes.get(minute) match {
              case Some(sm) =>
                s"${sm.fixedPoints},${sm.movements},${sm.shifts}"
              case _ => "Missing staffing data for this period,,"
            }
            val total = qcms.map(_.deskRec).sum
            val localMinute = SDate(minute, Crunch.europeLondonTimeZone)
            val misc = terminalStaffMinutes.get(minute).map(_.fixedPoints).getOrElse(0)
            s"${localMinute.toISODateOnly},${localMinute.prettyTime()},$qsCsv,$staffMinutesCsv,${total + misc}\n"

        }.mkString
      }
    })
  }

  def deskRecsCsv(cm: CrunchMinute): String =
      s"${Math.round(cm.paxLoad)},${cm.waitTime},${cm.deskRec},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"

  def deploymentsCsv(cm: CrunchMinute): String =
      s"${Math.round(cm.paxLoad)},${cm.deployedWait.getOrElse("")},${cm.deployedDesks.getOrElse(0)},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"
}
