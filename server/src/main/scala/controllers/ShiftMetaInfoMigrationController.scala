package controllers

import play.api.Logger
import play.api.mvc.InjectedController
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.{LegacyShiftAssignmentsService, ShiftAssignmentsService, ShiftMetaInfoService}
import uk.gov.homeoffice.drt.time.SDate

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ShiftMetaInfoMigrationController @Inject()(ctrl: DrtSystemInterface,
                                                 legacyShiftAssignmentsService: LegacyShiftAssignmentsService,
                                                 shiftAssignmentsService: ShiftAssignmentsService
                                                )(implicit ec: ExecutionContext) extends InjectedController {
  private val log = Logger(this.getClass)


  private def migrateStaffAssignments(port: String, terminal: String, now: Timestamp, shiftMetaInfoService: ShiftMetaInfoService) = {
    legacyShiftAssignmentsService.allShiftAssignments.flatMap { shiftAssignments =>
      shiftAssignmentsService.updateShiftAssignments(shiftAssignments.assignments).map { _ =>
        log.info(s"Successfully transferred legacy shift assignments for $port $terminal")
        shiftMetaInfoService.updateLastShiftAppliedAt(port, terminal, now).map { _ =>
          log.info(s"Successfully marked shift assignments as migrated for $port $terminal")
        }
      }.recoverWith {
        case ex: Exception =>
          log.error(s"Failed to update last shift applied at for $port $terminal: ${ex.getMessage}", ex)
          scala.concurrent.Future.successful(())
      }
    }.recoverWith {
      case ex: Exception =>
        log.error(s"Failed to transfer legacy shift assignments for $port $terminal: ${ex.getMessage}", ex)
        scala.concurrent.Future.successful(())
    }
  }

  private def markMigrationRecord(port: String, terminals: Seq[Terminals.Terminal], now: Timestamp, shiftMetaInfoService: ShiftMetaInfoService) = {
    terminals.map { terminal =>
      shiftMetaInfoService.insertShiftMetaInfo(port, terminal.toString, Some(now), None).map { _ =>
        log.info(s"Marked shift meta info for $port $terminal")
      }.recoverWith {
        case ex: Exception =>
          log.error(s"Failed to insert shift meta info for $port $terminal: ${ex.getMessage}", ex)
          scala.concurrent.Future.successful(0)
      }
    }
  }

  private def checkAndMarkShiftAssignmentsMigration(): Unit = {
    val now = new java.sql.Timestamp(System.currentTimeMillis())
    val port = ctrl.airportConfig.portCode.iata
    val terminals: Seq[Terminal] = ctrl.airportConfig.terminalsForDate(SDate.now().toLocalDate).toSeq
    val shiftMetaInfoService: ShiftMetaInfoService = ctrl.shiftMetaInfoService
    terminals.foreach { terminal =>
      if (ctrl.params.enableShiftPlanningChange) {
        shiftMetaInfoService.getShiftMetaInfo(port, terminal.toString).map {
          case Some(shiftMeta) if shiftMeta.shiftAssignmentsMigratedAt.isDefined =>
            log.info(s"Already migrated shift assignments as migrated for $port $terminal")
          case _ =>
            log.info(s"No existing shift meta info for $port $terminal, creating new entry and marking as migrated")
            markMigrationRecord(port, terminals, now, shiftMetaInfoService)
            migrateStaffAssignments(port, terminal.toString, now, shiftMetaInfoService)
        }.recoverWith {
          case ex: Exception =>
            log.error(s"Failed to mark shift assignments as migrated for $port $terminal: ${ex.getMessage}", ex)
            scala.concurrent.Future.successful(())
        }
      } else {
        log.info(s"enableShiftPlanningChange for $port $terminal is disabled, no needed to migrate shift assignments")
      }
    }
  }

  checkAndMarkShiftAssignmentsMigration()
}