package api

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.keycloak.{KeyCloakAuth, KeyCloakAuthError, KeyCloakAuthToken, KeyCloakAuthTokenParserProtocol}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class KeyCloakAuthSpec extends CrunchTestLike with KeyCloakAuthTokenParserProtocol {

  val keyCloakUrl = "https://keycloak"

  val tokenResponseJson: String =
    s"""{
       |   "access_token": "token",
       |   "expires_in": 86400,
       |   "refresh_expires_in": 86400,
       |   "refresh_token": "refresh token",
       |   "token_type": "bearer",
       |   "not-before-policy": 0,
       |   "session_state": "session",
       |   "scope": "profile email"
       |}""".stripMargin


  "When parsing keycloak JSON token I should get back a case class representation of the token" >> {

    import spray.json._

    val expected = KeyCloakAuthToken(
      "token",
      86400,
      86400,
      "refresh token",
      "bearer",
      0,
      "session",
      "profile email"
    )

    val result: KeyCloakAuthToken = tokenResponseJson.parseJson.convertTo[KeyCloakAuthToken]

    result === expected
  }

  "When logging into Keycloak with a correct username and password then I should get a token back" >> {

    val sendHttpRequest: HttpRequest => Future[HttpResponse] =
      _ => Future.successful(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, tokenResponseJson)))
    val auth = KeyCloakAuth("tokenurl", "clientId", "client secret", sendHttpRequest)

    val expected = KeyCloakAuthToken(
      "token",
      86400,
      86400,
      "refresh token",
      "bearer",
      0,
      "session",
      "profile email"
    )

    val token = Await.result(auth.getToken("user", "pass"), 30.seconds)

    token === expected
  }

  "When logging into Keycloak with an invalid username and password then I should handle the response" >> {

    val sendHttpRequest: HttpRequest => Future[HttpResponse] = (_: HttpRequest) => {
      Future(HttpResponse(400).withEntity(HttpEntity(
        ContentTypes.`application/json`,
        """|
             |{
           |  "error": "invalid_grant",
           |  "error_description": "Invalid user credentials"
           |}
          """.stripMargin
      )))
    }

    val auth = KeyCloakAuth("tokenurl", "clientId", "client secret", sendHttpRequest)

    val expected = KeyCloakAuthError("invalid_grant", "Invalid user credentials")

    val errorResponse = Await.result(auth.getToken("user", "pass"), 30.seconds)

    errorResponse === expected
  }

}
