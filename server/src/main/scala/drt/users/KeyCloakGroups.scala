package drt.users

import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class KeyCloakGroups(groups: List[KeyCloakGroup]) {
  def eventualUsersWithGroupsCsvContent(client: KeyCloakClient): Future[String] = {
    val usersWithGroupsFuture = eventualUsersWithGroups(groups, client)
    futureUsersWithGroupsToCsv(usersWithGroupsFuture)
  }

  def futureUsersWithGroupsToCsv(usersWithGroupsFuture: Future[List[(KeyCloakUser, String)]]): Future[String] = usersWithGroupsFuture
    .map(_.groupBy { case (user, _) => user })
    .map(usersToUsersWithGroups => {
      val csvLines = usersToUsersWithGroups
        .toSeq
        .map {
          case (user, usersWithGroups) =>
            val groupsCsvValue = usersWithGroups.map(_._2).sorted.mkString(", ")
            s"""${user.email},${user.firstName},${user.lastName},${user.enabled},"$groupsCsvValue""""
        }
      csvLines.mkString("\n")
    })

  def eventualUsersWithGroups(groups: List[KeyCloakGroup], client: KeyCloakClient): Future[List[(KeyCloakUser, String)]] = {
    val eventualUsersWithGroupsByGroup: List[Future[List[(KeyCloakUser, String)]]] = groups.map(group => {
      val eventualUsersWithGroups = client
        .getUsersInGroup(group.name)
        .map(_.map(user => (user, group.name)))
      eventualUsersWithGroups
    })
    Future.sequence(eventualUsersWithGroupsByGroup).map(_.flatten)
  }
}