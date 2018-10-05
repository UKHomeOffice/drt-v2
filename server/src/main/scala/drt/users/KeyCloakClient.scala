package drt.users

import java.util.UUID

import akka.actor.ActorSystem
import akka.util.Timeout
import drt.http.WithSendAndReceive
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import org.slf4j.LoggerFactory
import spray.client.pipelining.{Get, addHeaders, unmarshal, _}
import spray.http.HttpHeaders.{Accept, Authorization}
import spray.http.{HttpRequest, HttpResponse, MediaTypes, OAuth2BearerToken}
import spray.httpx.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsObject, JsValue, RootJsonFormat}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Future

abstract case class KeyCloakClient(token: String, keyCloakUrl: String, implicit val system: ActorSystem)
  extends WithSendAndReceive with KeyCloakUserParserProtocol {

  import system.dispatcher
  import KeyCloakUserParserProtocol.KeyCloakUserFormatParser._

  def log = LoggerFactory.getLogger(getClass)
  implicit val timeout: Timeout = Timeout(1 minute)

  def logResponse(requestName: String): HttpResponse => HttpResponse = resp => {

    if (resp.status.isFailure) {
      log.error(s"Error when calling $requestName on KeyCloak API Status code: ${resp.status} Response:<${resp.entity.asString}>")
    }

    resp
  }

  def getUsers(max: Int = 1000, offset: Int = 0): Future[List[KeyCloakUser]] = {
    val pipeline: HttpRequest => Future[List[KeyCloakUser]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse("getUsers")
        ~> unmarshal[List[KeyCloakUser]]
      )

    val uri = keyCloakUrl + s"/users?max=$max&first=$offset"
    log.info(s"Calling key cloak: $uri")
    pipeline(Get(uri))
  }

  def getUserGroups(userId: UUID): Future[List[KeyCloakGroup]] = {
    val pipeline: HttpRequest => Future[List[KeyCloakGroup]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse("getUserGroups")
        ~> unmarshal[List[KeyCloakGroup]]
      )
    val uri = keyCloakUrl + s"/users/$userId/groups"
    log.info(s"Calling key cloak: $uri")

    pipeline(Get(uri))
  }

  def getGroups(): Future[List[KeyCloakGroup]] = {
    val pipeline: HttpRequest => Future[List[KeyCloakGroup]] = (
        addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
    ~> sendAndReceive
    ~> logResponse("getGroups")
    ~> unmarshal[List[KeyCloakGroup]]
    )
    val uri = keyCloakUrl + "/groups"
    log.info(s"Calling key cloak: $uri")

    pipeline(Get(uri))
  }

  def getUsersInGroup(groupName: String, max: Int = 1000): Future[List[KeyCloakUser]] = {

    val futureMaybeId: Future[Option[String]] = getGroups().map(gs => gs.find(_.name == groupName).map(_.id))

    val pipeline: HttpRequest => Future[List[KeyCloakUser]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse("getUsersInGroup")
        ~> unmarshal[List[KeyCloakUser]]
      )

    futureMaybeId.flatMap {
      case Some(id) => pipeline(Get(keyCloakUrl + s"/groups/$id/members?max=$max"))
      case None => Future(List())
    }
  }

  def getUsersNotInGroup(groupName: String): Future[List[KeyCloakUser]] = {

    val futureUsersInGroup: Future[List[KeyCloakUser]] = getUsersInGroup(groupName)
    val futureAllUsers: Future[List[KeyCloakUser]] = getUsers()

    for {
      usersInGroup <- futureUsersInGroup
      allUsers <- futureAllUsers
    } yield allUsers.filterNot(usersInGroup.toSet)
  }

  def addUserToGroup(userId: UUID, groupId: String): Future[HttpResponse] = {
    val pipeline: HttpRequest => Future[HttpResponse] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse("addUserToGroup")
      )

    log.info(s"Adding $userId to $groupId")
    pipeline(Put(s"$keyCloakUrl/users/$userId/groups/$groupId"))
  }

  def removeUserFromGroup(userId: UUID, groupId: String): Future[HttpResponse] = {
    val pipeline: HttpRequest => Future[HttpResponse] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse("removeUserFromGroup")
      )

    log.info(s"Removing $userId from $groupId")
    pipeline(Delete(s"$keyCloakUrl/users/$userId/groups/$groupId"))
  }
}


trait KeyCloakUserParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit object KeyCloakUserFormatParser extends RootJsonFormat[KeyCloakUser] {
    override def write(obj: KeyCloakUser): JsValue = ???

    override def read(json: JsValue): KeyCloakUser = json match {
      case JsObject(fields) =>
        KeyCloakUser(
          UUID.fromString(fields.get("id").map(_.convertTo[String]).getOrElse("")),
          fields.get("username").map(_.convertTo[String]).getOrElse(""),
          fields.get("enabled").exists(_.convertTo[Boolean]),
          fields.get("emailVerified").exists(_.convertTo[Boolean]),
          fields.get("firstName").map(_.convertTo[String]).getOrElse(""),
          fields.get("lastName").map(_.convertTo[String]).getOrElse(""),
          fields.get("email").map(_.convertTo[String]).getOrElse("")
        )
    }
  }
  implicit val keyCloakGroupFormat: RootJsonFormat[KeyCloakGroup] = jsonFormat3(KeyCloakGroup)
}


object KeyCloakUserParserProtocol extends KeyCloakUserParserProtocol


