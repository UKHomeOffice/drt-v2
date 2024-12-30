package controllers.application

import actors.persistent.staffing.StaffingUtil
import drt.shared.{ShiftAssignments, StaffShift}
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.auth.Roles.{FixedPointsView, StaffEdit}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.StaffShiftsPlanService
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import upickle.default.{read, write}

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
      case _ => throw new DeserializationException("Expected LocalDate as JsObject with year, month, and day")
    }
  }

  implicit val staffShiftFormat: RootJsonFormat[StaffShift] = new RootJsonFormat[StaffShift] {
    override def write(shift: StaffShift): JsValue = JsObject(
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

    override def read(json: JsValue): StaffShift = {
      val fields = json.asJsObject.fields
      StaffShift(
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

  def convertTo(text: String): Seq[StaffShift] = {
    val json = text.parseJson
    json.convertTo[Seq[StaffShift]]
  }


}

class StaffShiftsController @Inject()(cc: ControllerComponents,
                                      ctrl: DrtSystemInterface,
                                      staffShiftsPlanService: StaffShiftsPlanService)(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) with StaffShiftsJson {

  def getDefaultShift(port: String, terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.getShift(port, terminal, shiftName).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getDefaultShifts(port: String, terminal: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.getShifts(port, terminal).map { shifts =>
      Ok(shifts.toJson.compactPrint)
    }
  }

  def saveDefaultShift: Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val shifts = convertTo(text)
        ctrl.staffShiftsService.saveShift(shifts).flatMap { result =>
          staffShiftsPlanService.allShifts.flatMap { allShifts =>
            val startTime = System.currentTimeMillis()
            val updatedAssignments = StaffingUtil.updateWithDefaultShift(shifts, allShifts)
            println(s"Updated assignments in ${System.currentTimeMillis() - startTime} ms")
            staffShiftsPlanService.updateShifts(updatedAssignments).map { _ =>
              Ok(s"Inserted $result shift(s)")
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


  def getShifts(localDateStr: String): Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val date = SDate(localDateStr).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      staffShiftsPlanService.shiftsForDate(date, maybePointInTime)
        .map(sa => Ok(write(sa)))
    }
  }

  def getAllShifts: Action[AnyContent] = Action.async {
    staffShiftsPlanService.allShifts.map(s => Ok(write(s)))
  }

  def saveShifts: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { request =>
      request.body.asText match {
        case Some(text) =>
          val shifts = read[ShiftAssignments](text)
          staffShiftsPlanService
            .updateShifts(shifts.assignments)
            .map(allShifts => Accepted(write(allShifts)))
        case None =>
          Future.successful(BadRequest)
      }
    }
  }

  def removeShift(port: String, terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.deleteShift(port, terminal, shiftName).map { result =>
      Ok(s"Deleted $result shift(s)")
    }
  }
}
