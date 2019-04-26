package drt.shared

import ujson.Js.Value
import upickle.Js
import upickle.default._
import upickle.default.{macroRW, ReadWriter => RW}

case class LoggedInUser(userName: String, id: String, email: String, roles: Set[Role]) {
  def hasRole(role: Role) = roles.exists(_.name == role.name)
}

object LoggedInUser {
  implicit val rw: RW[AirportConfig] = macroRW
}

case class ShouldReload(shouldReload: Boolean)

sealed trait Role {
  val name: String
}

object Role {
  implicit val paxTypeReaderWriter: ReadWriter[Role] =
    readwriter[Js.Value].bimap[Role](
      r => r.name,
      (s: Value) => Roles.parse(s.toString())
        .getOrElse(NoOpRole)
    )
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
    TestAccess,
    Test2Access,
    STNAccess
  )
  val availableRoles: Set[Role] = Set(
    StaffEdit,
    ApiView,
    ManageUsers,
    CreateAlerts,
    ApiViewPortCsv
  ) ++ portRoles

  def parse(roleName: String): Option[Role] = availableRoles.find(role => role.name == roleName)
}

case object NoOpRole extends Role {
  override val name: String = "noop"
}

case object StaffEdit extends Role {
  override val name: String = "staff:edit"
}

case object ApiView extends Role {
  override val name: String = "api:view"
}

case object ApiViewPortCsv extends Role {
  override val name: String = "api:view-port-arrivals"
}

case object TestAccess extends Role {
  override val name: String = "test"
}

case object Test2Access extends Role {
  override val name: String = "test2"
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
}

case object CreateAlerts extends Role {
  override val name: String = "create-alerts"
}
