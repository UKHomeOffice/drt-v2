error id: file://<WORKSPACE>/server/src/main/scala/controllers/application/ShiftsController.scala:
file://<WORKSPACE>/server/src/main/scala/controllers/application/ShiftsController.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 6205
uri: file://<WORKSPACE>/server/src/main/scala/controllers/application/ShiftsController.scala
text:
```scala
package controllers.application

import actors.persistent.staffing.StaffingUtil
import drt.shared.Shift
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil.{addAmonth, convertToSqlDate, dateFromString, toStaffShiftRow}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import upickle.default.write

import java.sql.Date
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

  def convertTo(text: String): Seq[Shift] = {
    val json = text.parseJson
    json.convertTo[Seq[Shift]]
  }

  def convertToShift(text: String): Shift = {
    val json = text.parseJson
    json.convertTo[Shift]
  }
}

class ShiftsController @Inject()(cc: ControllerComponents,
                                 ctrl: DrtSystemInterface,
                                 shiftAssignmentsService: ShiftAssignmentsService,
                                )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) with StaffShiftsJson {

  def getShift(port: String, terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShift(port, terminal, shiftName, new Date(System.currentTimeMillis())).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getAShift(port: String, terminal: String, shiftName: String, startDate: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShift(port, terminal, shiftName, dateFromString(startDate)).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getShifts(port: String, terminal: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getShifts(port, terminal).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShiftsForDate(port: String, terminal: String, date: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShifts(port, terminal, Option(date)).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def getActiveShifts(port: String, terminal: String): Action[AnyContent] = Action.async {
    ctrl.shiftsService.getActiveShifts(port, terminal, None).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def saveShifts: Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val shifts = convertTo(text)
        ctrl.shiftsService.saveShift(shifts).flatMap { result =>
          shiftAssignmentsService.allShiftAssignments.flatMap { allShiftAssignments =>
            val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShiftAssignments)
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

  def updateShift(): Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val shift = convertToShift(text)
//        Future.successful(Ok("shift received: " + shift.toString))
        ctrl.shiftsService.getShift(shift.port, shift.terminal, shift.shiftName, convertToSqlDate(shift.startDate)).flatMap {
          case Some(previousShift) =>
            println(s"......@@previousShift $previousShift")
            ctrl.shiftsService.updateShift(previousShift, shift).flatMap { _ =>
              shiftAssignmentsService.allShiftAssignments.flatMap { allShiftAssignments =>
                //                ctrl.shiftsService.getOverlappingStaffShifts(shift.port, shift.terminal, toStaffShiftRow(shift, None, None, new java.sql.Timestamp(System.currentTimeMillis()))).flatMap { overridingShifts =>
                val updatedAssignments = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, Seq.empty, shift, allShiftAssignments)
                shiftAssignmentsService.updateShiftAssignments(updatedAssignments).map { s =>
                  Ok(write(s))
                }
                //                }
              }.recoverWith {
                case e: Exception => Future.successful(BadRequest(s"Failed to update shifts: ${e.getMessage}"))
              }
            }
          case None =>
            Future.successful(BadRequest("Previous shift not found"))
        }
      } catch {
        case _: DeserializationException => Future.successful(BadRequest("Invalid shift format"))
      }
    }.getOrElse(Future.successful(BadRequest("Expecting JSON data")))
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: 