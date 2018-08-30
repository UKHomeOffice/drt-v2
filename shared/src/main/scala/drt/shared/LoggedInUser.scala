package drt.shared

case class LoggedInUser(userName: String, id: String, email: String, roles: Set[Role])

sealed trait Role{
  val name: String
}

object Roles {
  val portRoles: Set[Role] = Set(
    BHXAccess,
    BRSAccess,
    EDIAccess,
    EMAAccess,
    LGWAccess,
    LHRAccess,
    LTNAccess,
    MANAccess,
    STNAccess,
    CreateAlerts
  )
  val availableRoles : Set[Role] = Set(
    StaffEdit,
    ApiView,
    ManageUsers
  ) ++ portRoles
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

case object BHXAccess extends Role {
  override val name: String = "BHX"
}

case object BRSAccess extends Role {
  override val name: String = "BRS"
}

case object EDIAccess extends Role {
  override val name: String = "EDI"
}

case object EMAAccess extends Role {
  override val name: String = "EMA"
}

case object LGWAccess extends Role {
  override val name: String = "LGW"
}

case object LHRAccess extends Role {
  override val name: String = "LHR"
}

case object LTNAccess extends Role {
  override val name: String = "LTN"
}

case object MANAccess extends Role {
  override val name: String = "MAN"
}

case object STNAccess extends Role {
  override val name: String = "STN"
}case object CreateAlerts extends Role {
  override val name: String = "create-alerts"
}
