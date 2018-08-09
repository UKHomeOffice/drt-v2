package drt.shared

case class LoggedInUser(userName: String, id: String, email: String, roles: List[Role])

sealed trait Role{
  val name: String
}

object Roles {
  val availableRoles = List(StaffEdit, DrtTeam, ManageUsers)
  def parse(roleName: String): Option[Role] = availableRoles.find(role=> role.name == roleName)
}

case object StaffEdit extends Role {
  override val name: String = "staff:edit"
}

case object DrtTeam extends Role {
  override val name: String = "drt:team"
}

case object ManageUsers extends Role {
  override val name: String = "manage-users"
}