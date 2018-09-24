package drt.shared

case class LoggedInUser(userName: String, id: String, email: String, roles: List[Role])

sealed trait Role{
  val name: String
}

object Roles {
  val availableRoles = List(StaffEdit, ApiView, ManageUsers, CreateAlerts)
  def parse(roleName: String): Option[Role] = availableRoles.find(role=> role.name == roleName)
}

case object StaffEdit extends Role {
  override val name: String = "staff:edit"
}

case object ApiView extends Role {
  override val name: String = "api:view"
}

case object ManageUsers extends Role {
  override val name: String = "manage-users"
}

case object CreateAlerts extends Role {
  override val name: String = "create-alerts"
}
