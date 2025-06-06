package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.models.UserPreferences

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case object GetUserPreferences extends Action

case class SetUserPreferences(userPreferences: UserPreferences) extends Action

case class UpdateUserPreferences(userPreferences: UserPreferences) extends Action

class UserPreferencesHandler[M](modelRW: ModelRW[M, Pot[UserPreferences]]) extends LoggingActionHandler(modelRW) {
  import upickle.default._
  implicit val rw: ReadWriter[UserPreferences] = macroRW
  implicit val portDashboardIntervalMinutesRW: ReadWriter[Map[String, Int]] =
    readwriter[String].bimap[Map[String, Int]](
      _.map { case (port, value) => s"$port:$value" }.mkString(";"),
      s => if (s.isEmpty) Map.empty[String, Int]
      else s.split(";").map(_.split(":") match {
        case Array(port, value) => port -> value.toInt
      }).toMap
    )

  implicit val portDashboardTerminalsRW: ReadWriter[Map[String, Set[String]]] =
    readwriter[String].bimap[Map[String, Set[String]]](
      _.map { case (key, values) => s"$key:${values.mkString(",")}" }.mkString(";"),
      s => if (s.isEmpty) Map.empty[String, Set[String]]
      else s.split(";").map(_.split(":") match {
        case Array(key, values) => key -> values.split(",").toSet
      }).toMap
    )

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetUserPreferences =>
      val apiCallEffect = Effect(DrtApi.get("data/user-preferences")
        .map(r => SetUserPreferences(read[UserPreferences](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get User Preferences data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserPreferences, PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)


    case SetUserPreferences(userPreferences) => updated(Ready(userPreferences))

    case UpdateUserPreferences(userPreferences) =>
      val apiCallEffect = Effect(DrtApi.post("data/user-preferences", s"${write(userPreferences)}")
        .map(_ => SetUserPreferences(userPreferences))
        .recoverWith {
          case _ =>
            log.error(s"Failed to update User Preferences data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateUserPreferences(userPreferences), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
