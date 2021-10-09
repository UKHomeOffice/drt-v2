package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.graphstages.Crunch
import services.{AirportToCountry, SDate}
import spray.json.{DefaultJsonProtocol, JsArray, JsNumber, JsObject, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.auth.Roles.EgateBanksEdit
import uk.gov.homeoffice.drt.egates.{DeleteEgateBanksUpdates, EgateBanksUpdate, EgateBanksUpdates, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.PortCode
import upickle.default._

import scala.concurrent.Future


trait WithEgateBanks {
  self: Application =>

  def getEgateBanksPorts(dateString: String): Action[AnyContent] =
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[EgateBanksUpdates].map { redListUpdates =>
        val forDate = SDate(dateString, Crunch.europeLondonTimeZone).millisSinceEpoch
        val redListPorts = AirportToCountry.airportInfoByIataPortCode.values.collect {
          case AirportInfo(_, _, country, portCode) if redListUpdates.countryCodesByName(forDate).contains(country) =>
            PortCode(portCode)
        }

        Ok(write(redListPorts))
      }
    }

  def getEgateBanksUpdates: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: EgateBanksJsonFormats.redListUpdatesJsonFormat.type = EgateBanksJsonFormats.redListUpdatesJsonFormat
      ctrl.redListUpdatesActor.ask(GetState).mapTo[EgateBanksUpdates].map(r => Ok(r.toJson.compactPrint))
    }

  def getEgateBanksUpdatesLegacy: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: EgateBanksJsonFormats.redListUpdatesJsonFormat.type = EgateBanksJsonFormats.redListUpdatesJsonFormat
      ctrl.redListUpdatesActor.ask(GetState).mapTo[EgateBanksUpdates].map(r => Ok(write(r)))
    }

  def updateEgateBanksUpdates(): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            import spray.json._

            implicit val rdu = EgateBanksJsonFormats.redListUpdateJsonFormat
            implicit val rdus = EgateBanksJsonFormats.redListUpdatesJsonFormat
            implicit val srdu = EgateBanksJsonFormats.setEgateBanksUpdatesJsonFormat

            val setUpdate = text.parseJson.convertTo[SetEgateBanksUpdate]

            ctrl.redListUpdatesActor.ask(setUpdate).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }
    }
  }

  def deleteEgateBanksUpdates(effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      ctrl.redListUpdatesActor.ask(DeleteEgateBanksUpdates(effectiveFrom)).map(_ => Accepted)
    }
  }
}


object EgateBanksJsonFormats extends DefaultJsonProtocol {
    implicit object redListUpdateJsonFormat extends RootJsonFormat[EgateBanksUpdate] {
      override def write(obj: EgateBanksUpdate): JsValue = {
        val additions = obj.additions.map { case (name, code) =>
          JsArray(JsString(name), JsString(code))
        }
        JsObject(Map(
          "effectiveFrom" -> JsNumber(obj.effectiveFrom),
          "additions" -> JsArray(additions.toVector),
          "removals" -> JsArray(obj.removals.map(r => JsString(r)).toVector)))
      }

      override def read(json: JsValue): EgateBanksUpdate = json match {
        case JsObject(fields) =>
          val maybeStuff = for {
            effectiveFrom <- fields.get("effectiveFrom").collect { case JsNumber(value) => value.toLong }
            additions <- fields.get("additions").collect {
              case JsArray(things) =>
                val namesWithCodes = things.collect {
                  case JsArray(nameAndCode) =>
                    nameAndCode.toList match {
                      case JsString(n) :: JsString(c) :: _ => (n, c)
                      case _ => throw new Exception("Didn't find country name and code for EgateBanksUpdate additions")
                    }
                  case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
                }.toMap
                namesWithCodes
              case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
            }
            removals <- fields.get("removals").collect { case JsArray(stuff) => stuff.map(_.convertTo[String]).toList }
          } yield EgateBanksUpdate(effectiveFrom, additions, removals)
          maybeStuff.getOrElse(throw new Exception("Failed to deserialise EgateBanksUpdate json"))
      }
    }

  implicit object redListUpdatesJsonFormat extends RootJsonFormat[EgateBanksUpdates] {
    override def write(obj: EgateBanksUpdates): JsValue = JsArray(obj.updates.values.map(_.toJson).toVector)

    override def read(json: JsValue): EgateBanksUpdates = json match {
      case JsObject(fields) => fields.get("updates") match {
        case Some(JsObject(updates)) =>
          val redListUpdates = updates.map {
            case (effectiveFrom, redListUpdateJson) => (effectiveFrom.toLong, redListUpdateJson.convertTo[EgateBanksUpdate])
          }
          EgateBanksUpdates(redListUpdates)
      }
    }
  }

  implicit val setEgateBanksUpdatesJsonFormat: RootJsonFormat[SetEgateBanksUpdate] = jsonFormat2(SetEgateBanksUpdate.apply)

  implicit val portCodeJsonFormat: RootJsonFormat[PortCode] = jsonFormat(PortCode.apply, "iata")
}
