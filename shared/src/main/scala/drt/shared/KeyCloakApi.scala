package drt.shared

object KeyCloakApi {

  case class KeyCloakUser(
                           id: String,
                           createdTimestamp: Long,
                           username: String,
                           enabled: Boolean,
                           totp: Boolean,
                           emailVerified: Boolean,
                           firstName: String,
                           lastName: String,
                           email: String,
                           disableableCredentialTypes: Seq[String],
                           requiredActions: Seq[String],
                           notBefore: Int
                         )

  case class KeyCloakGroup(
                            id: String,
                            name: String,
                            path: String,
                            subGroups: List[String]
                          )
}
