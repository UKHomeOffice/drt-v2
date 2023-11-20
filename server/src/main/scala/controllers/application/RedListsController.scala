package controllers.application

import akka.pattern.ask
import com.google.inject.Inject
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.AirportToCountry
import services.graphstages.Crunch
import spray.json.{DefaultJsonProtocol, JsArray, JsNumber, JsObject, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, RedListUpdates, SetRedListUpdate}
import uk.gov.homeoffice.drt.time.SDate
import upickle.default._


class RedListsController@Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getRedListPorts(dateString: String): Action[AnyContent] =
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map { redListUpdates =>
        val forDate = SDate(dateString, Crunch.europeLondonTimeZone).millisSinceEpoch
        val redListPorts = AirportToCountry.airportInfoByIataPortCode.values.collect {
          case AirportInfo(_, _, country, portCode) if redListUpdates.countryCodesByName(forDate).contains(country) =>
            PortCode(portCode)
        }

        Ok(write(redListPorts))
      }
    }

  def getRedListUpdates: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: RedListJsonFormats.redListUpdatesJsonFormat.type = RedListJsonFormats.redListUpdatesJsonFormat
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map(r => Ok(r.toJson.compactPrint))
    }

  def getRedListUpdatesLegacy: Action[AnyContent] =
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map(r => Ok(write(r)))
    }
}


object RedListJsonFormats extends DefaultJsonProtocol {
    implicit object redListUpdateJsonFormat extends RootJsonFormat[RedListUpdate] {
      override def write(obj: RedListUpdate): JsValue = {
        val additions = obj.additions.map { case (name, code) =>
          JsArray(JsString(name), JsString(code))
        }
        JsObject(Map(
          "effectiveFrom" -> JsNumber(obj.effectiveFrom),
          "additions" -> JsArray(additions.toVector),
          "removals" -> JsArray(obj.removals.map(r => JsString(r)).toVector)))
      }

      override def read(json: JsValue): RedListUpdate = json match {
        case JsObject(fields) =>
          val maybeStuff = for {
            effectiveFrom <- fields.get("effectiveFrom").collect { case JsNumber(value) => value.toLong }
            additions <- fields.get("additions").collect {
              case JsArray(things) =>
                val namesWithCodes = things.collect {
                  case JsArray(nameAndCode) =>
                    nameAndCode.toList match {
                      case JsString(n) :: JsString(c) :: _ => (n, c)
                      case _ => throw new Exception("Didn't find country name and code for RedListUpdate additions")
                    }
                  case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
                }.toMap
                namesWithCodes
              case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
            }
            removals <- fields.get("removals").collect { case JsArray(stuff) => stuff.map(_.convertTo[String]).toList }
          } yield RedListUpdate(effectiveFrom, additions, removals)
          maybeStuff.getOrElse(throw new Exception("Failed to deserialise RedListUpdate json"))
      }
    }

  implicit object redListUpdatesJsonFormat extends RootJsonFormat[RedListUpdates] {
    override def write(obj: RedListUpdates): JsValue = JsArray(obj.updates.values.map(_.toJson).toVector)

    override def read(json: JsValue): RedListUpdates = json match {
      case JsObject(fields) => fields.get("updates") match {
        case Some(JsObject(updates)) =>
          val redListUpdates = updates.map {
            case (effectiveFrom, redListUpdateJson) => (effectiveFrom.toLong, redListUpdateJson.convertTo[RedListUpdate])
          }
          RedListUpdates(redListUpdates)
      }
    }
  }

  implicit val setRedListUpdatesJsonFormat: RootJsonFormat[SetRedListUpdate] = jsonFormat2(SetRedListUpdate.apply)

  implicit val portCodeJsonFormat: RootJsonFormat[PortCode] = jsonFormat(PortCode.apply, "iata")
}
