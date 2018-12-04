package api

import akka.actor.ActorSystem
import drt.http.WithSendAndReceive
import spray.http.{FormData, HttpRequest, MediaTypes}
import spray.httpx.SprayJsonSupport
import spray.httpx.UnsuccessfulResponseException
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}
import spray.client.pipelining.{Get, addHeaders, unmarshal, _}
import spray.http.HttpHeaders.Accept

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

abstract case class KeyCloakAuth(tokenUrl: String, clientId: String, clientSecret: String, implicit val system: ActorSystem) extends WithSendAndReceive with KeyCloakAuthTokenParserProtocol{

  import system.dispatcher
  import KeyCloakAuthTokenFormatParser._

  def formData(username: String, password: String, clientId: String, clientSecret: String) = FormData(Seq(
  "username" -> username,
  "password" -> password,
  "client_id" -> clientId,
  "client_secret" -> clientSecret,
  "grant_type" -> "password"
  ))

  def getToken(username: String, password: String): Future[KeyCloakAuthResponse] = {
    val pipeline: HttpRequest => Future[KeyCloakAuthToken] = (
    addHeaders(Accept(MediaTypes.`application/json`))
    ~> sendAndReceive
    ~> unmarshal[KeyCloakAuthToken]
    )

    pipeline(Post(tokenUrl, formData(username, password, clientId, clientSecret))).recoverWith{
      case e: UnsuccessfulResponseException =>
        import spray.json._
        import KeyCloakAuthErrorParser._
        Future(e.response.entity.asString.parseJson.convertTo[KeyCloakAuthError])
    }
  }
}

trait KeyCloakAuthResponse

case class KeyCloakAuthToken(
  accessToken: String,
  expiresIn: Int,
  refreshExpiresIn: Int,
  refreshToken: String,
  tokenType: String,
  notBeforePolicy: Int,
  sessionState: String,
  scope: String
) extends KeyCloakAuthResponse

case class KeyCloakAuthError(error: String, errorDescription: String) extends KeyCloakAuthResponse

object KeyCloakAuthTokenParserProtocol extends KeyCloakAuthTokenParserProtocol

trait KeyCloakAuthTokenParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit object KeyCloakAuthTokenFormatParser extends RootJsonFormat[KeyCloakAuthToken] {
    override def write(token: KeyCloakAuthToken): JsValue = JsObject(
      "access_token" -> JsString(token.accessToken),
      "expires_in" -> JsNumber(token.expiresIn),
      "token_type" -> JsString(token.tokenType)
    )

    override def read(json: JsValue): KeyCloakAuthToken = json match {
      case JsObject(fields) =>
        KeyCloakAuthToken(
          fields.get("access_token").map(_.convertTo[String]).getOrElse(""),
          fields.get("expires_in").map(_.convertTo[Int]).getOrElse(0),
          fields.get("refresh_expires_in").map(_.convertTo[Int]).getOrElse(0),
          fields.get("refresh_token").map(_.convertTo[String]).getOrElse(""),
          fields.get("token_type").map(_.convertTo[String]).getOrElse(""),
          fields.get("not-before-policy").map(_.convertTo[Int]).getOrElse(0),
          fields.get("session_state").map(_.convertTo[String]).getOrElse(""),
          fields.get("scope").map(_.convertTo[String]).getOrElse("")
        )
    }
  }

  implicit object KeyCloakAuthErrorParser extends RootJsonFormat[KeyCloakAuthError] {
    override def write(error: KeyCloakAuthError): JsValue = JsObject(
      "error" -> JsString(error.error),
      "error_description" -> JsString(error.errorDescription)
    )

    override def read(json: JsValue): KeyCloakAuthError = json match {
      case JsObject(fields) =>
        KeyCloakAuthError(
          fields.get("error").map(_.convertTo[String]).getOrElse(""),
          fields.get("error_description").map(_.convertTo[String]).getOrElse("")
        )
    }
  }

}
