package services.crunch.staffing

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute, StaffMinutes}
import drt.shared.{FixedPointAssignments, ShiftAssignments, StaffMovements, TM}
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.DrtRunnableGraph
import services.graphstages.Staffing
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

object RunnableStaffing extends DrtRunnableGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import SDate.implicits.sdateFromMillisLocal

  def apply(staffingQueueActor: ActorRef,
            staffQueue: SortedSet[ProcessingRequest],
            crunchRequest: MillisSinceEpoch => CrunchRequest,
            shiftsActor: ActorRef,
            fixedPointsActor: ActorRef,
            movementsActor: ActorRef,
            staffMinutesActor: ActorRef,
            now: () => SDateLike,
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ec: ExecutionContext, timeout: Timeout, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val shiftsProvider = (r: ProcessingRequest) => shiftsActor.ask(r).mapTo[ShiftAssignments]
    val fixedPointsProvider = (r: ProcessingRequest) => fixedPointsActor.ask(r).mapTo[FixedPointAssignments]
    val movementsProvider = (r: ProcessingRequest) => movementsActor.ask(r).mapTo[StaffMovements]

    val staffMinutesFlow = RunnableStaffing.staffMinutesFlow(shiftsProvider, fixedPointsProvider, movementsProvider, now, setUpdatedAtForDay)

    val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) =
      startQueuedRequestProcessingGraph(
        minutesProducer = staffMinutesFlow,
        persistentQueueActor = staffingQueueActor,
        initialQueue = staffQueue,
        sinkActor = staffMinutesActor,
        graphName = "staffing",
        processingRequest = crunchRequest,
      )
    (staffingUpdateRequestQueue, staffingUpdateKillSwitch)
  }

  def staffMinutesFlow(shiftsProvider: ProcessingRequest => Future[ShiftAssignments],
                       fixedPointsProvider: ProcessingRequest => Future[FixedPointAssignments],
                       movementsProvider: ProcessingRequest => Future[StaffMovements],
                       now: () => SDateLike,
                       setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                      )
                      (implicit ec: ExecutionContext): Flow[ProcessingRequest, MinutesContainer[StaffMinute, TM], NotUsed] =
    Flow[ProcessingRequest]
      .wireTap(processingRequest => log.info(s"${processingRequest.date} staffing crunch request started"))
      .mapAsync(1)(cr => shiftsProvider(cr).map(sa => (cr, sa)))
      .mapAsync(1) { case (cr, sa) => fixedPointsProvider(cr).map(fp => (cr, sa, fp)) }
      .mapAsync(1) { case (cr, sa, fp) => movementsProvider(cr).map(sm => (cr, sa, fp, sm)) }
      .via(toStaffMinutes(now, setUpdatedAtForDay))

  private def toStaffMinutes(now: () => SDateLike,
                             setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                            ): Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements), MinutesContainer[StaffMinute, TM], NotUsed] =
    Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements)]
      .collect { case (processingRequest: TerminalUpdateRequest, sa, fp, sm) =>
        setUpdatedAtForDay(processingRequest.terminal, processingRequest.date, now().millisSinceEpoch)

        val staff = Staffing.staffAvailableByTerminalAndQueue(processingRequest.start.millisSinceEpoch, sa, fp, Option(sm.movements))

        StaffMinutes(processingRequest.minutesInMillis.map { minute =>
          val m = SDate(minute, europeLondonTimeZone)
          val shifts = staff.shifts.terminalStaffAt(processingRequest.terminal, m, sdateFromMillisLocal)
          val fixedPoints = staff.fixedPoints.terminalStaffAt(processingRequest.terminal, m, sdateFromMillisLocal)
          val movements = staff.movements.terminalStaffAt(processingRequest.terminal, minute)

          StaffMinute(processingRequest.terminal, minute, shifts, fixedPoints, movements, lastUpdated = Option(now().millisSinceEpoch))
        }).asContainer
      }
}
