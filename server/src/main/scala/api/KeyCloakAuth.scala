package api

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import drt.http.WithSendAndReceive
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

import scala.concurrent.Future

abstract case class KeyCloakAuth(tokenUrl: String, clientId: String, clientSecret: String)(implicit val system: ActorSystem, mat: Materializer)
  extends WithSendAndReceive with KeyCloakAuthTokenParserProtocol {

  import system.dispatcher

  val log: Logger = LoggerFactory.getLogger(getClass)

  def formData(username: String, password: String, clientId: String, clientSecret: String) = FormData(Map(
    "username" -> username,
    "password" -> password,
    "client_id" -> clientId,
    "client_secret" -> clientSecret,
    "grant_type" -> "password"
  ))

  def getToken(username: String, password: String): Future[KeyCloakAuthResponse] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = Uri(tokenUrl),
      headers = List(Accept(MediaTypes.`application/json`)),
      entity = formData(username, password, clientId, clientSecret).toEntity)

    val requestWithHeaders = request.addHeader(Accept(MediaTypes.`application/json`))

    sendAndReceive(requestWithHeaders).flatMap { r =>
      Unmarshal(r).to[KeyCloakAuthResponse]
    }
  }
}

sealed trait KeyCloakAuthResponse

case class KeyCloakAuthToken(accessToken: String,
                             expiresIn: Int,
                             refreshExpiresIn: Int,
                             refreshToken: String,
                             tokenType: String,
                             notBeforePolicy: Int,
                             sessionState: String,
                             scope: String) extends KeyCloakAuthResponse

case class KeyCloakAuthError(error: String, errorDescription: String) extends KeyCloakAuthResponse

object KeyCloakAuthTokenParserProtocol extends KeyCloakAuthTokenParserProtocol


trait KeyCloakAuthTokenParserProtocol extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val responseFormat: RootJsonFormat[KeyCloakAuthResponse] = new RootJsonFormat[KeyCloakAuthResponse] {
    override def write(response: KeyCloakAuthResponse): JsValue = response match {
      case KeyCloakAuthToken(token, expires, _, _, tokenType, _, _, _) => JsObject(
        "access_token" -> JsString(token),
        "expires_in" -> JsNumber(expires),
        "token_type" -> JsString(tokenType)
      )
      case KeyCloakAuthError(error, desc) => JsObject(
        "error" -> JsString(error),
        "error_description" -> JsString(desc)
      )
    }

    override def read(json: JsValue): KeyCloakAuthResponse = json match {
      case JsObject(fields) if fields.contains("access_token") =>
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
      case JsObject(fields) =>
        KeyCloakAuthError(
          fields.get("error").map(_.convertTo[String]).getOrElse(""),
          fields.get("error_description").map(_.convertTo[String]).getOrElse("")
        )
    }
  }

  implicit val tokenFormat: RootJsonFormat[KeyCloakAuthToken] = new RootJsonFormat[KeyCloakAuthToken] {
    override def write(token: KeyCloakAuthToken): JsValue = JsObject(
      "access_token" -> JsString(token.accessToken),
      "expires_in" -> JsNumber(token.expiresIn),
      "token_type" -> JsString(token.tokenType)
    )

    override def read(json: JsValue): KeyCloakAuthToken = json match {
      case JsObject(fields) if fields.contains("access_token") =>
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

  implicit val errorFormat: RootJsonFormat[KeyCloakAuthError] = new RootJsonFormat[KeyCloakAuthError] {
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
