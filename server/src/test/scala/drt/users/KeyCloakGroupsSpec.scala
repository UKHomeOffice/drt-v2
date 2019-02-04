package drt.users

import java.util.UUID

import akka.actor.ActorSystem
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import org.specs2.mutable.Specification
import spray.http.{HttpRequest, HttpResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


class KeyCloakGroupsSpec extends Specification {
  val staticUuid: UUID = UUID.randomUUID()

  def usernameToUser(username: String): KeyCloakUser = KeyCloakUser(
    staticUuid,
    username,
    enabled = true,
    emailVerified = true,
    "",
    "",
    username
  )

  val testUsers: Map[String, KeyCloakUser] = List(
    "user1",
    "user2",
    "user3",
    "user4",
    "user5",
    "user6",
    "user7",
    "user8",
    "user9"
  ).map(name => name -> usernameToUser(name))
    .toMap

  val testGroups = List(
    KeyCloakGroup("", "BHX", ""),
    KeyCloakGroup("", "EDI", ""),
    KeyCloakGroup("", "EMA", ""),
    KeyCloakGroup("", "LHR", ""),
    KeyCloakGroup("", "LGW", "")
  )

  class TestKeyCloakClient(groupUsers: Map[String, List[KeyCloakUser]], allUsers: Map[Int, List[KeyCloakUser]])
    extends KeyCloakClient("", "", ActorSystem("test")) {

    override def sendAndReceive: HttpRequest => Future[HttpResponse] = _ => Future(HttpResponse())

    override def getUsersInGroup(groupName: String, max: Int = 1000): Future[List[KeyCloakUser]] = Future(
      groupUsers(groupName)
    )

    override def getUsers(max: Int, offset: Int): Future[List[KeyCloakUser]] = Future(allUsers.getOrElse(offset, List()))
  }

  val usersByGroup: Map[String, List[KeyCloakUser]] = Map(
    "BHX" -> List(testUsers("user1"), testUsers("user2")),
    "EDI" -> List(testUsers("user2")),
    "EMA" -> List(testUsers("user1")),
    "LHR" -> List(testUsers("user3"), testUsers("user4"), testUsers("user1")),
    "LGW" -> List(testUsers("user1"), testUsers("user4"))
  )

  "Given a list of groups and a corresponding list of users in each of those groups " +
    "When I ask for a csv export of users with their groups " +
    "Then I should get a map of users to their groups" >> {

    val usersPage1 = List(
      testUsers("user1"),
      testUsers("user2"),
      testUsers("user3"),
      testUsers("user4")
    )

    val fakeClient = new TestKeyCloakClient(usersByGroup, Map(0 -> usersPage1))


    val result = Await.result(KeyCloakGroups(testGroups, fakeClient)
      .usersWithGroupsByUser(testGroups), 2 seconds)

    val resultByUserNameToSet: Map[String, Set[String]] = result.map {
      case (user, g) => user.username -> g.toSet
    }

    val expected: Map[String, Set[String]] = Map(
      "user1" -> Set("BHX", "EMA", "LHR", "LGW"),
      "user2" -> Set("BHX", "EDI"),
      "user3" -> Set("LHR"),
      "user4" -> Set("LHR", "LGW")
    )

    resultByUserNameToSet === expected
  }

  "Given a list of groups and a corresponding list of users in each of those groups " +
    "When I ask for a csv export of users with their groups " +
    "Then I should see a list of users with all the groups each is in" >> {

    val usersPage1 = List(
      testUsers("user1"),
      testUsers("user2"),
      testUsers("user3"),
      testUsers("user4")
    )

    val fakeClient = new TestKeyCloakClient(usersByGroup, Map(0 -> usersPage1))

    val result = Await
      .result(KeyCloakGroups(testGroups, fakeClient)
        .usersWithGroupsCsvContent
        .map(_.split("\n").toSet), 1 second)

    val expected = Set(
      """Email,First Name,Last Name,Enabled,Groups""",
      """user1,,,true,"BHX, EMA, LGW, LHR"""",
      """user2,,,true,"BHX, EDI"""",
      """user3,,,true,"LHR"""",
      """user4,,,true,"LGW, LHR""""
    )

    result === expected
  }

  "Given a list of groups and a corresponding list of users in each of those groups " +
    "And a number of users who are not in any group"
  "When I ask for a csv export of users with their groups " +
    "Then I should see a list of users with all the groups each is in and all the users with no  groups" >> {

    val usersPage1 = List(
      testUsers("user1"),
      testUsers("user2"),
      testUsers("user3"),
      testUsers("user4"),
      testUsers("user5"),
      testUsers("user6")
    )

    val usersPage2 = List(
      testUsers("user7"),
      testUsers("user8"),
      testUsers("user9")
    )

    val fakeClient = new TestKeyCloakClient(usersByGroup, Map(0 -> usersPage1, 50 -> usersPage2))

    val result = Await
      .result(KeyCloakGroups(testGroups, fakeClient)
        .usersWithGroupsCsvContent
        .map(_.split("\n").toSet), 1 second)

    val expected = Set(
      """Email,First Name,Last Name,Enabled,Groups""",
      """user1,,,true,"BHX, EMA, LGW, LHR"""",
      """user2,,,true,"BHX, EDI"""",
      """user3,,,true,"LHR"""",
      """user4,,,true,"LGW, LHR"""",
      """user5,,,true,""""",
      """user6,,,true,""""",
      """user7,,,true,""""",
      """user8,,,true,""""",
      """user9,,,true,"""""
    )

    result === expected
  }
}
