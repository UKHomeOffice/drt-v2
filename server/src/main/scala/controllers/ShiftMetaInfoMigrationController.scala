package controllers

import play.api.Logger
import play.api.mvc.InjectedController
import uk.gov.homeoffice.drt.ShiftMeta
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.{LegacyShiftAssignmentsService, ShiftAssignmentsService, ShiftMetaInfoService}
import uk.gov.homeoffice.drt.time.SDate

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

trait IShiftMetaInfoMigration {

  private val log = Logger(this.getClass)

  implicit val ec: ExecutionContext

  val ctrl: DrtSystemInterface

  val legacyShiftAssignmentsService: LegacyShiftAssignmentsService

  val shiftAssignmentsService: ShiftAssignmentsService

  private def migrateStaffAssignments(port: String, terminal: String, now: Timestamp) = {
    legacyShiftAssignmentsService.allShiftAssignments.flatMap { shiftAssignments =>
      shiftAssignmentsService.updateShiftAssignments(shiftAssignments.assignments).map { _ =>
        log.info(s"Successfully transferred legacy shift assignments for $port $terminal")
      }.recoverWith {
        case ex: Exception =>
          log.error(s"Failed to update last shift applied at for $port $terminal: ${ex.getMessage}", ex)
          scala.concurrent.Future.failed(ex)
      }
    }.recoverWith {
      case ex: Exception =>
        log.error(s"Failed to transfer legacy shift assignments for $port $terminal: ${ex.getMessage}", ex)
        scala.concurrent.Future.failed(ex)
    }
  }

  private def markMigrationRecord(port: String, terminal: Terminal, now: Timestamp, shiftMetaInfoService : ShiftMetaInfoService): Future[Any] = {
      shiftMetaInfoService.insertShiftMetaInfo(ShiftMeta(port, terminal.toString, Some(now.getTime))).map { _ =>
        log.info(s"Marked shift meta info for $port $terminal")
      }.recoverWith {
        case ex: Exception =>
          log.error(s"Failed to insert shift meta info for $port $terminal: ${ex.getMessage}", ex)
          scala.concurrent.Future.failed(ex)
      }
    }


  def checkAndMarkShiftAssignmentsMigration(dateTime: Long, shiftMetaInfoService : ShiftMetaInfoService): Future[Seq[Any]] = {
    val now = new java.sql.Timestamp(dateTime)
    val port = ctrl.airportConfig.portCode.iata
    val terminals: Seq[Terminal] = ctrl.airportConfig.terminalsForDate(SDate(dateTime).toLocalDate).toSeq
    Future.sequence(terminals.map { terminal =>
      if (ctrl.params.enableShiftPlanningChange) {
        shiftMetaInfoService.getShiftMetaInfo(port, terminal.toString).map {
          case Some(shiftMeta) if shiftMeta.shiftAssignmentsMigratedAt.isDefined =>
            log.info(s"Already migrated shift assignments as migrated for $port $terminal")
            Future.successful("No action taken")
          case _ =>
            log.info(s"No existing shift meta info for $port $terminal, creating new entry and marking as migrated")
            migrateStaffAssignments(port, terminal.toString, now).map { _ =>
              markMigrationRecord(port, terminal, now , shiftMetaInfoService).map { _ =>
                  log.info(s"Marked shift assignments as migrated for $port $terminal")
                  Future.successful("Migration completed")
                }
                .recoverWith {
                  case ex: Exception =>
                    log.error(s"Failed to mark shift assignments as migrated for $port $terminal: ${ex.getMessage}", ex)
                    scala.concurrent.Future.failed(ex)
                }
            }.recoverWith {
              case ex: Exception =>
                log.error(s"Failed to migrate shift assignments for $port $terminal: ${ex.getMessage}", ex)
                scala.concurrent.Future.failed(ex)
            }
        }.recoverWith {
          case ex: Exception =>
            log.error(s"Failed to get shift meta info for $port $terminal: ${ex.getMessage}", ex)
            scala.concurrent.Future.failed(ex)
        }
      } else {
        log.info(s"enableShiftPlanningChange for $port $terminal is disabled, no needed to migrate shift assignments")
        Future.successful("No action taken")
      }
    })
  }
}

@Singleton
class ShiftMetaInfoMigrationController @Inject()(
                                                  override val ctrl: DrtSystemInterface,
                                                  override val legacyShiftAssignmentsService: LegacyShiftAssignmentsService,
                                                  override val shiftAssignmentsService: ShiftAssignmentsService)(implicit val ec: ExecutionContext)
  extends InjectedController with IShiftMetaInfoMigration {

  checkAndMarkShiftAssignmentsMigration(System.currentTimeMillis() , ctrl.shiftMetaInfoService)
}
