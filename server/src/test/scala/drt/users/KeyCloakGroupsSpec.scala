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

  "Given a list of groups and a corresponding list of users in each of those groups " +
    "When I ask for a csv export of users with their groups " +
    "Then I should see a list of users with all the groups each is in" >> {

    val groups = List(
      KeyCloakGroup("", "BHX", ""),
      KeyCloakGroup("", "EDI", ""),
      KeyCloakGroup("", "EMA", ""),
      KeyCloakGroup("", "LHR", ""),
      KeyCloakGroup("", "LGW", "")
    )

    val fakeClient = new KeyCloakClient("", "", ActorSystem("test")) {

      override def sendAndReceive: HttpRequest => Future[HttpResponse] = _ => Future(HttpResponse())

      override def getUsersInGroup(groupName: String, max: Int = 1000): Future[List[KeyCloakUser]] = {
        val usersInGroup = groupName match {
          case "BHX" => List("user1", "user2")
          case "EDI" => List("user2")
          case "EMA" => List("user1")
          case "LHR" => List("user3", "user4", "user1")
          case "LGW" => List("user1", "user4")
        }

        Future(usersInGroup.map(username => KeyCloakUser(staticUuid, username, enabled = true, emailVerified = true, "", "", username)))
      }
    }

    val result = Await.result(KeyCloakGroups(groups).usersWithGroupsCsvContent(fakeClient).map(_.split("\n").toSet), 1 second)

    val expected = Set(
      """user1,,,true,"BHX, EMA, LGW, LHR"""",
      """user2,,,true,"BHX, EDI"""",
      """user3,,,true,"LHR"""",
      """user4,,,true,"LGW, LHR""""
    )

    result === expected
  }
}
