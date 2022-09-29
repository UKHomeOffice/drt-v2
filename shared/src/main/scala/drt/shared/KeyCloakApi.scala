package drt.shared

import upickle.default.{macroRW, _}

object KeyCloakApi  {
  implicit val rw: ReadWriter[KeyCloakUser] = macroRW

  case class KeyCloakUser(
                           id: String,
                           username: String,
                           enabled: Boolean,
                           emailVerified: Boolean,
                           firstName: String,
                           lastName: String,
                           email: String
                         )

  case class KeyCloakGroup(
                            id: String,
                            name: String,
                            path: String
                          )
}
