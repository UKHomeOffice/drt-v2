package drt.users

import akka.actor.ActorSystem
import akka.util.Timeout
import drt.http.WithSendAndReceive
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import org.slf4j.LoggerFactory
import spray.client.pipelining.{Get, addHeaders, unmarshal, _}
import spray.http.HttpHeaders.{Accept, Authorization}
import spray.http.{HttpRequest, HttpResponse, MediaTypes, OAuth2BearerToken}
import spray.httpx.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Future

abstract case class KeyCloakClient(token: String, keyCloakUrl: String, implicit val system: ActorSystem)
  extends WithSendAndReceive with KeyCloakUserParserProtocol {

  import system.dispatcher

  def log = LoggerFactory.getLogger(getClass)
  implicit val timeout: Timeout = Timeout(1 minute)

  val logResponse: HttpResponse => HttpResponse = resp => {

    if (resp.status.isFailure) {
      log.error(s"Error when reading KeyCloak API ${resp.headers}, ${resp.entity.data.asString}")
    }

    resp
  }

  def getUsers(): Future[List[KeyCloakUser]] = {
    val pipeline: HttpRequest => Future[List[KeyCloakUser]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse
        ~> unmarshal[List[KeyCloakUser]]
      )

    pipeline(Get(keyCloakUrl + "/users"))
  }

  def getGroups(): Future[List[KeyCloakGroup]] = {
    val pipeline: HttpRequest => Future[List[KeyCloakGroup]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse
        ~> unmarshal[List[KeyCloakGroup]]
      )

    pipeline(Get(keyCloakUrl + "/groups"))
  }

  def getUsersInGroup(groupName: String): Future[List[KeyCloakUser]] = {

    val futureMaybeId: Future[Option[String]] = getGroups().map(gs => gs.find(_.name == groupName).map(_.id))

    val pipeline: HttpRequest => Future[List[KeyCloakUser]] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse
        ~> unmarshal[List[KeyCloakUser]]
      )

    futureMaybeId.flatMap {
      case Some(id) => pipeline(Get(keyCloakUrl + s"/groups/$id/members"))
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

  def addUserToGroup(userId: String, groupId: String): Future[HttpResponse] = {
    val pipeline: HttpRequest => Future[HttpResponse] = (
      addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token)))
        ~> sendAndReceive
        ~> logResponse
      )

    log.info(s"Adding $userId to $groupId")
    pipeline(Put(s"$keyCloakUrl/users/$userId/groups/$groupId"))
  }
}


trait KeyCloakUserParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val keyCloakUserFormat: RootJsonFormat[KeyCloakUser] = jsonFormat7(KeyCloakUser)
  implicit val keyCloakGroupFormat: RootJsonFormat[KeyCloakGroup] = jsonFormat4(KeyCloakGroup)
}


object KeyCloakUserParserProtocol extends KeyCloakUserParserProtocol


