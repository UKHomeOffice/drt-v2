package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay, StaffMovementMinute, ViewMode}
import drt.shared.{StaffMovement, StaffMovements, TM}
import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class StaffMovementsHandler[M](getCurrentViewMode: () => ViewMode,
                               modelRW: ModelRW[M, (Pot[StaffMovements], Map[TM, Seq[StaffMovementMinute]], Set[String])]) extends LoggingActionHandler(modelRW) {
  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetStaffMovements(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovements(staffMovements) =>
      value match {
        case (Ready(sms), _, _) =>
          val updatedStaffMovements = StaffMovements((sms.movements ++ staffMovements).sortBy(_.time))
          effectOnly(Effect(DrtApi.post("staff-movements", write(staffMovements))
            .map(_ => SetStaffMovements(updatedStaffMovements)).recover {
              case _ =>
                RetryActionAfter(AddStaffMovements(staffMovements), PollDelay.recoveryDelay)
            }))

        case _ => noChange
      }

    case RecordClientSideStaffMovement(terminal, start, end, staff) =>
      val updatedMinutes = (start until end by oneMinuteMillis).map { minute =>
        val existing = value._2.getOrElse(TM(terminal, minute), Seq.empty[StaffMovementMinute])
        val newMinute = StaffMovementMinute(terminal, minute, staff, SDate.now().millisSinceEpoch)
        TM(terminal, minute) -> (existing :+ newMinute)
      }
      updated(value._1, value._2 ++ updatedMinutes, value._3)

    case RemoveStaffMovements(movementsPairUuid) =>
      value match {
        case (Ready(sms), localAdded, localRemoved) =>
          val updatedStaffMovements = StaffMovements(sms.movements.filterNot(_.uUID == movementsPairUuid).sortBy(_.time))
          updated(
            (Ready(updatedStaffMovements), localAdded, localRemoved + movementsPairUuid),
            Effect(DrtApi.delete(s"staff-movements/$movementsPairUuid")
              .map(_ => SetStaffMovements(updatedStaffMovements)).recover {
                case _ =>
                  RetryActionAfter(RemoveStaffMovements(movementsPairUuid), PollDelay.recoveryDelay)
              }))

        case _ => noChange
      }

    case SetStaffMovements(sms) =>
      updated((Ready(sms), value._2, value._3))

    case SetStaffMovementsAndPollIfLiveView(viewMode, staffMovements) =>
      if (viewMode.isHistoric(SDate.now()))
        updated((Ready(staffMovements), value._2, value._3))
      else
        updated((Ready(staffMovements), value._2, value._3), scheduledRequest(viewMode))

    case GetStaffMovements(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetStaffMovements(viewMode) =>
      val uri = s"staff-movements/${viewMode.localDate}" +
        viewMode.maybePointInTime.map(pit => s"?pointInTime=$pit").getOrElse("")

      val apiCallEffect = Effect(DrtApi.get(uri)
        .map { res =>
          SetStaffMovementsAndPollIfLiveView(viewMode, StaffMovements(read[List[StaffMovement]](res.responseText)))
        }
        .recover {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            RetryActionAfter(GetStaffMovements(viewMode), PollDelay.recoveryDelay)
        })

      effectOnly(apiCallEffect)
  }
}
