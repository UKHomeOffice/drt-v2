package drt.users

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.users.KeyCloakUserParserProtocol._
import org.specs2.mutable.SpecificationLike
import spray.http.{HttpMethods, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class KeyCloakApiSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {

  val keyCloakUrl = "https://keycloak"

  val userId1 = UUID.fromString("e25f2a14-bdaa-11e8-a355-529269fb1459")
  val userId2 = UUID.fromString("e25f2dfc-bdaa-11e8-a355-529269fb1459")

  val usersJson: String =
    s"""[{
      |        "id": "$userId1",
      |        "createdTimestamp": 1516283371483,
      |        "username": "test1@digital.homeoffice.gov.uk",
      |        "enabled": true,
      |        "totp": false,
      |        "emailVerified": true,
      |        "firstName": "Man",
      |        "lastName": "One",
      |        "email": "test1@digital.homeoffice.gov.uk",
      |        "disableableCredentialTypes": [
      |            "password"
      |        ],
      |        "requiredActions": [],
      |        "notBefore": 0,
      |        "access": {
      |            "manageGroupMembership": true,
      |            "view": true,
      |            "mapRoles": true,
      |            "impersonate": true,
      |            "manage": true
      |        }
      |    },
      |    {
      |        "id": "${userId2}",
      |        "createdTimestamp": 1516967531289,
      |        "username": "test2@homeoffice.gsi.gov.uk",
      |        "enabled": true,
      |        "totp": false,
      |        "emailVerified": true,
      |        "firstName": "Man",
      |        "lastName": "Two",
      |        "email": "test2@digital.homeoffice.gov.uk",
      |        "disableableCredentialTypes": [
      |            "password"
      |        ],
      |        "requiredActions": [],
      |        "notBefore": 0,
      |        "access": {
      |            "manageGroupMembership": true,
      |            "view": true,
      |            "mapRoles": true,
      |            "impersonate": true,
      |            "manage": true
      |        }
      |    }]""".stripMargin

val usersMissingOptionalFieldsJson =
    s"""[{
      |        "id": "$userId1",
      |        "username": "test1@digital.homeoffice.gov.uk",
      |        "enabled": true,
      |        "emailVerified": true,
      |        "firstName": "Man",
      |        "lastName": "One",
      |        "email": "test1@digital.homeoffice.gov.uk",
      |        "requiredActions": [],
      |        "notBefore": 0
      |    },
      |    {
      |        "id": "$userId2",
      |        "createdTimestamp": 1516967531289,
      |        "username": "test2@homeoffice.gsi.gov.uk",
      |        "enabled": true,
      |        "totp": false,
      |        "emailVerified": true,
      |        "firstName": "Man",
      |        "lastName": "Two",
      |        "email": "test2@digital.homeoffice.gov.uk",
      |        "disableableCredentialTypes": [
      |            "password"
      |        ],
      |        "requiredActions": [],
      |        "notBefore": 0,
      |        "access": {
      |            "manageGroupMembership": true,
      |            "view": true,
      |            "mapRoles": true,
      |            "impersonate": true,
      |            "manage": true
      |        }
      |    }]""".stripMargin

  private val user1 = KeyCloakUser(
    userId1,
    "test1@digital.homeoffice.gov.uk",
    true,
    true,
    "Man",
    "One",
    "test1@digital.homeoffice.gov.uk"
  )
  private val user2 = KeyCloakUser(
    userId2,
    "test2@homeoffice.gsi.gov.uk",
    true,
    true,
    "Man",
    "Two",
    "test2@digital.homeoffice.gov.uk"
  )
  val expectedUsers = List(
    user1,
    user2
  )
  "When parsing a JSON list of users from key cloak I should get back a list of KeyCloakUsers" >> {

    import spray.json._

    val result = usersJson.parseJson.convertTo[List[KeyCloakUser]]
    result == expectedUsers
  }

  "When querying the keycloak API to get a list of all users " +
    "Given an auth token then I should get back a list of users" >> {

    val token = "testToken"
    val kc = new KeyCloakClient(token, keyCloakUrl, system) {
      def sendAndReceive: (HttpRequest) => Future[HttpResponse] = (req: HttpRequest) => {

        Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, usersJson)))
      }
    }

    val users: List[KeyCloakUser] = Await.result(kc.getUsers(), 30 seconds)

    users === expectedUsers
  }


  val groupsJson =
    """    [{
      |        "id": "id1",
      |        "name": "DRT Admin User",
      |        "path": "/DRT Admin User",
      |        "subGroups": []
      |    },
      |    {
      |        "id": "id2",
      |        "name": "LHR",
      |        "path": "/LHR",
      |        "subGroups": []
      |    }]""".stripMargin

  private val lhrGroup = KeyCloakGroup("id2", "LHR", "/LHR")
  val expectedGroups = List(
    KeyCloakGroup("id1", "DRT Admin User", "/DRT Admin User"),
    lhrGroup
  )

  "When parsing a JSON list of users from key cloak I should get back a list of KeyCloakUsers" >> {
    import spray.json._

    val result = usersJson.parseJson.convertTo[List[KeyCloakUser]]

    result == expectedUsers
  }
  "When parsing a JSON list of users missing some optional fields the I should still get a list of Users" >> {
    import spray.json._

    val result = usersJson.parseJson.convertTo[List[KeyCloakUser]]

    result == expectedUsers
  }

  "When querying the keycloak API to get a list of all groups I should get back a list of groups" >> {

    val token = "testToken"
    val kc = new KeyCloakClient(token, keyCloakUrl, system) {
      def sendAndReceive: (HttpRequest) => Future[HttpResponse] = (req: HttpRequest) => {
        Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, groupsJson)))
      }
    }

    val groups: List[KeyCloakGroup] = Await.result(kc.getGroups, 30 seconds)

    groups === expectedGroups
  }

  "When querying the keycloak API to get a list of users in LHR then I should get back user2" >> {
    val token = "testToken"
    val kc = new KeyCloakClient(token, keyCloakUrl, system) with MockServerForUsersInGroup

    val users: List[KeyCloakUser] = Await.result(kc.getUsersInGroup("LHR"), 30 seconds)

    users === List(user2)
  }

  "When asking for users not in LHR I should get back user1" >> {
    val token = "testToken"
    val kc = new KeyCloakClient(token, keyCloakUrl, system) with MockServerForUsersInGroup

    val users: List[KeyCloakUser] = Await.result(kc.getUsersNotInGroup("LHR"), 30 seconds)

    users === List(user1)
  }

  "When adding a user to a group the user and group should be posted to the correct keycloak endpoint" >> {
    val token = "testToken"
    val kc = new KeyCloakClient(token, keyCloakUrl, system) with MockServerForUsersInGroup

    val res: HttpResponse = Await.result(kc.addUserToGroup(user1.id, lhrGroup.id), 30 seconds)

    res.status === StatusCodes.NoContent
  }

  val lhrUsers = s"""
    | [{
    |        "id": "$userId2",
    |        "createdTimestamp": 1516967531289,
    |        "username": "test2@homeoffice.gsi.gov.uk",
    |        "enabled": true,
    |        "totp": false,
    |        "emailVerified": true,
    |        "firstName": "Man",
    |        "lastName": "Two",
    |        "email": "test2@digital.homeoffice.gov.uk",
    |        "disableableCredentialTypes": [
    |            "password"
    |        ],
    |        "requiredActions": [],
    |        "notBefore": 0,
    |        "access": {
    |            "manageGroupMembership": true,
    |            "view": true,
    |            "mapRoles": true,
    |            "impersonate": true,
    |            "manage": true
    |        }
    |  }]
  """.stripMargin

  trait MockServerForUsersInGroup {

    def sendAndReceive: (HttpRequest) => Future[HttpResponse] = (req: HttpRequest) => {
      req.uri.toString.replace(keyCloakUrl, "") match {
        case "/groups/id2/members?max=1000" =>
          Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, lhrUsers)))
        case "/groups" =>
          Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, groupsJson)))
        case "/users?max=1000&first=0" =>
          Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, usersJson)))
        case "/users/e25f2a14-bdaa-11e8-a355-529269fb1459/groups/id2" =>
          assert(req.method == HttpMethods.PUT)
          Future(HttpResponse(StatusCodes.NoContent))
      }
    }
  }
}
