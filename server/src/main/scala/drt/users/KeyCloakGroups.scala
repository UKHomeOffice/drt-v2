package drt.users

import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class KeyCloakGroups(groups: List[KeyCloakGroup], client: KeyCloakClient) {
  def usersWithGroupsCsvContent: Future[String] = {
    val usersWithGroupsFuture = allUsersWithGroups(groups)
    usersWithGroupsToCsv(usersWithGroupsFuture)
  }

  def usersWithGroupsToCsv(usersWithGroupsFuture: Future[Map[KeyCloakUser, List[String]]]): Future[String] = {
    val headerLine = "Email,First Name,Last Name,Enabled,Groups"
    usersWithGroupsFuture
      .map(usersToUsersWithGroups => {
        val csvLines = usersToUsersWithGroups
          .map {
            case (user, userGroups) =>
              val userGroupsCsvValue = userGroups.sorted.mkString(", ")
              s"""${user.email},${user.firstName},${user.lastName},${user.enabled},"$userGroupsCsvValue""""
          }
        headerLine + "\n" + csvLines.mkString("\n")
      })
  }

  def usersWithGroups(groups: List[KeyCloakGroup]): Future[List[(KeyCloakUser, String)]] = {
    val eventualUsersWithGroupsByGroup: List[Future[List[(KeyCloakUser, String)]]] = groups.map(group => {
      val eventualUsersWithGroups = client
        .getUsersInGroup(group.name)
        .map(_.map(user => (user, group.name)))
      eventualUsersWithGroups
    })
    Future.sequence(eventualUsersWithGroupsByGroup).map(_.flatten)
  }

  def usersWithGroupsByUser(groups: List[KeyCloakGroup]): Future[Map[KeyCloakUser, List[String]]] =
    usersWithGroups(groups).map(usersAndGroups => {
      usersAndGroups.groupBy {
        case (user, group) => user
      }.mapValues(_.map {
        case (_, group) => group
      })
    })

  def allUsersWithGroups(groups: List[KeyCloakGroup]): Future[Map[KeyCloakUser, List[String]]] =
    usersWithGroupsByUser(groups).map(groupsByUser => {
      client.getAllUsers().map(u => {
        u -> groupsByUser.getOrElse(u, List())
      }).toMap
    })
}
