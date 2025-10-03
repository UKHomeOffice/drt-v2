package controllers.application

import actors.persistent.staffing.StaffingUtil
import actors.persistent.staffing.StaffingUtil.updateWithShiftDefaultStaff
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.util.ShiftUtil.{currentLocalDate, localDateFromString}
import upickle.default.write

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

trait StaffShiftsJson extends DefaultJsonProtocol {

  implicit val localDateFormat: RootJsonFormat[LocalDate] = new RootJsonFormat[LocalDate] {
    override def write(date: LocalDate): JsValue = JsObject(
      "year" -> JsNumber(date.year),
      "month" -> JsNumber(date.month),
      "day" -> JsNumber(date.day)
    )

    override def read(json: JsValue): LocalDate = json.asJsObject.getFields("year", "month", "day") match {
      case Seq(JsNumber(year), JsNumber(month), JsNumber(day)) =>
        LocalDate(year.toInt, month.toInt, day.toInt)
      case _ => throw DeserializationException("Expected LocalDate as JsObject with year, month, and day")
    }
  }

  implicit val staffShiftFormat: RootJsonFormat[Shift] = new RootJsonFormat[Shift] {
    override def write(shift: Shift): JsValue = JsObject(
      "port" -> JsString(shift.port),
      "terminal" -> JsString(shift.terminal),
      "shiftName" -> JsString(shift.shiftName),
      "startDate" -> shift.startDate.toJson,
      "startTime" -> JsString(shift.startTime),
      "endTime" -> JsString(shift.endTime),
      "endDate" -> shift.endDate.toJson,
      "staffNumber" -> JsNumber(shift.staffNumber),
      "frequency" -> shift.frequency.toJson,
      "createdBy" -> shift.createdBy.toJson,
      "createdAt" -> JsNumber(shift.createdAt)
    )

    override def read(json: JsValue): Shift = {
      val fields = json.asJsObject.fields
      Shift(
        port = fields("port").convertTo[String],
        terminal = fields("terminal").convertTo[String],
        shiftName = fields("shiftName").convertTo[String],
        startDate = fields("startDate").convertTo[LocalDate],
        startTime = fields("startTime").convertTo[String],
        endTime = fields("endTime").convertTo[String],
        endDate = fields.get("endDate") match {
          case Some(JsArray(elements)) if elements.isEmpty => None
          case Some(JsNull) => None
          case Some(value) => Some(value.convertTo[LocalDate])
          case None => None
        },
        staffNumber = fields("staffNumber").convertTo[Int],
        frequency = fields.get("frequency").flatMap {
          case JsString(value) => Some(value)
          case JsNull => None
          case _ => None
        },
        createdBy = fields.get("createdBy").flatMap {
          case JsString(value) => Some(value)
          case JsNull => None
          case _ => None
        },
        createdAt = fields("createdAt").convertTo[Long]
      )
    }
  }

  def shiftsFromJson(text: String): Seq[Shift] = {
    val json = text.parseJson
    json.convertTo[Seq[Shift]]
  }

  def shiftFromJson(text: String): Shift = {
    val json = text.parseJson
    json.convertTo[Shift]
  }
}

class ShiftsController @Inject()(cc: ControllerComponents,
                                 ctrl: DrtSystemInterface,
                                 shiftAssignmentsService: ShiftAssignmentsService,
                                )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) with StaffShiftsJson {

  def getShift(terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShift(ctrl.airportConfig.portCode.iata, terminal, shiftName, currentLocalDate).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getAShift(terminal: String, shiftName: String, startDate: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShift(ctrl.airportConfig.portCode.iata, terminal, shiftName, localDateFromString(startDate)).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getShifts(terminal: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShifts(ctrl.airportConfig.portCode.iata, terminal).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShiftsForDate(terminal: String, date: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShifts(ctrl.airportConfig.portCode.iata, terminal, Option(date)).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShiftsForViewRange(terminal: String, dayRange: String, date: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShiftsForViewRange(ctrl.airportConfig.portCode.iata, terminal, Option(dayRange), Option(date)).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShiftsForCurrentViewRange(terminal: String, dayRange: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShiftsForViewRange(ctrl.airportConfig.portCode.iata, terminal, Option(dayRange), None).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShifts(terminal: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShifts(ctrl.airportConfig.portCode.iata, terminal, None).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def saveShifts: Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val shifts = shiftsFromJson(text)
        ctrl.shiftsService.saveShift(shifts).flatMap { _ =>
          shiftAssignmentsService.allShiftAssignments.flatMap { allShiftAssignments =>
            val updatedAssignments = updateWithShiftDefaultStaff(shifts, allShiftAssignments)
            shiftAssignmentsService.updateShiftAssignments(updatedAssignments).map { s =>
              Ok(write(s))
            }
          }.recoverWith {
            case e: Exception => Future.successful(BadRequest(s"Failed to update shifts: ${e.getMessage}"))
          }
        }
      } catch {
        case _: DeserializationException => Future.successful(BadRequest("Invalid shift format"))
      }
    }.getOrElse(Future.successful(BadRequest("Expecting JSON data")))
  }

  private def updateAssignments(previousShift: Shift, futureExistingShift: Option[Shift], updatedNewShift: Shift) = {
    shiftAssignmentsService.allShiftAssignments.flatMap { allShiftAssignments =>
      ctrl.shiftsService.getOverlappingStaffShifts(updatedNewShift.port, updatedNewShift.terminal, updatedNewShift)
        .flatMap { overridingShifts =>
          val updatedAssignments = StaffingUtil.updateAssignmentsForShiftChange(
            previousShift,
            overridingShifts,
            futureExistingShift,
            updatedNewShift,
            allShiftAssignments)
          shiftAssignmentsService.updateShiftAssignments(updatedAssignments).map { s =>
            Ok(write(s))
          }.recoverWith {
            case e: Exception =>
              log.error(s"Failed to update shifts with assignments: ", e.printStackTrace())
              Future.successful(BadRequest(s"Failed to update shifts: ${e.getMessage}"))
          }
        }
    }.recoverWith {
      case e: Exception =>
        log.error(s"Failed to update shifts: ", e.printStackTrace())
        Future.successful(BadRequest(s"Failed to update shifts: ${e.getMessage}"))
    }
  }

  def updateShift(previousName: String): Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val newShift = shiftFromJson(text)
        ctrl.shiftsService.getShift(newShift.port, newShift.terminal, previousName, newShift.startDate).flatMap {
          case Some(previousShift) =>
            ctrl.shiftsService.updateShift(previousShift, newShift).flatMap { updatedNewShift =>
              updateAssignments(previousShift, None, updatedNewShift)
            }
          case None =>
            ctrl.shiftsService.latestStaffShiftForADate(newShift.port, newShift.terminal, newShift.startDate, newShift.startTime).flatMap {
              case Some(previousShift) =>
                ctrl.shiftsService.createNewShiftWhileEditing(previousShift, newShift).flatMap { case (updatedNewShift, futureExistingShift) =>
                  updateAssignments(previousShift, futureExistingShift, updatedNewShift)
                }
              case None =>
                Future.successful(BadRequest(s"Previous shift not found for update: $previousName on ${newShift.startDate}"))
            }.recoverWith {
              case e: Exception =>
                log.error(s"Failed to find previous shift for update: ", e.printStackTrace())
                Future.successful(BadRequest(s"Failed to find previous shift for update: ${e.getMessage}"))
            }
        }
      } catch {
        case _: DeserializationException => Future.successful(BadRequest("Invalid shift format"))
      }
    }.getOrElse(Future.successful(BadRequest("Expecting JSON data")))
  }
}
