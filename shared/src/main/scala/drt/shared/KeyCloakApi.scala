package drt.shared

import java.util.UUID

object KeyCloakApi {

  case class KeyCloakUser(
                           id: UUID,
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
