package drt.users

import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.{Accept, Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.util.Timeout
import drt.http.WithSendAndReceive
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, JsObject, JsValue, RootJsonFormat}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{Await, Future}

abstract case class KeyCloakClient(token: String, keyCloakUrl: String)(implicit val system: ActorSystem, mat: Materializer)
  extends WithSendAndReceive with KeyCloakUserParserProtocol {

  import system.dispatcher
  import KeyCloakUserParserProtocol.KeyCloakUserFormatParser._

  def log: Logger = LoggerFactory.getLogger(getClass)

  implicit val timeout: Timeout = Timeout(1 minute)

  val logResponse: HttpResponse => HttpResponse = { resp =>
    log.info(s"Response Object: $resp")
    log.debug(s"Response: ${resp.entity.toString}")
    if (resp.status.isFailure) {
      log.warn(s"Failed to talk to chroma ${resp.headers}")
      log.error(s"Failed to talk to chroma: entity ${resp.entity.toString}")
    }

    resp
  }

  def pipeline(method: HttpMethod, uri: String): Future[HttpResponse] = {
    val request = HttpRequest(method, Uri(uri))
    val requestWithHeaders = request
      .addHeader(Accept(MediaTypes.`application/json`))
      .addHeader(Authorization(OAuth2BearerToken(token)))
    sendAndReceive(requestWithHeaders).map { r =>
      logResponse(r)
      r
    }
  }

  def getUsers(max: Int = 100, offset: Int = 0): Future[List[KeyCloakUser]] = {
    val uri = keyCloakUrl + s"/users?max=$max&first=$offset"
    log.info(s"Calling key cloak: $uri")
    pipeline(HttpMethods.GET, uri).flatMap { r => Unmarshal(r).to[List[KeyCloakUser]] }
  }

  def getAllUsers(offset: Int = 0): Seq[KeyCloakUser] = {

    val users = Await.result(getUsers(50, offset), 2 seconds)

    if (users.isEmpty) Nil else users ++ getAllUsers(offset + 50)
  }

  def getUserGroups(userId: UUID): Future[List[KeyCloakGroup]] = {
    val uri = keyCloakUrl + s"/users/$userId/groups"
    log.info(s"Calling key cloak: $uri")
    pipeline(HttpMethods.GET, uri).flatMap { r => Unmarshal(r).to[List[KeyCloakGroup]] }
  }

  def getGroups: Future[List[KeyCloakGroup]] = {
    val uri = keyCloakUrl + "/groups"
    log.info(s"Calling key cloak: $uri")
    pipeline(HttpMethods.GET, uri).flatMap { r => Unmarshal(r).to[List[KeyCloakGroup]] }
  }

  def getUsersInGroup(groupName: String, max: Int = 1000): Future[List[KeyCloakUser]] = {
    val futureMaybeId: Future[Option[String]] = getGroups.map(gs => gs.find(_.name == groupName).map(_.id))

    futureMaybeId.flatMap {
      case Some(id) =>
        val uri = keyCloakUrl + s"/groups/$id/members?max=$max"
        pipeline(HttpMethods.GET, uri).flatMap { r => Unmarshal(r).to[List[KeyCloakUser]] }
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
    log.info(s"Adding $userId to $groupId")
    val uri = s"$keyCloakUrl/users/$userId/groups/$groupId"
    pipeline(HttpMethods.PUT, uri)
  }

  def removeUserFromGroup(userId: UUID, groupId: String): Future[HttpResponse] = {
    log.info(s"Removing $userId from $groupId")
    val uri = s"$keyCloakUrl/users/$userId/groups/$groupId"
    pipeline(HttpMethods.DELETE, uri)
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


