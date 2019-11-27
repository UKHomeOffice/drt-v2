package drt.server

import drt.shared.PortCode

package object feeds {
  import scala.language.implicitConversions

  object Implicits {
    implicit def portCodeFromString(str: String): PortCode = PortCode(str)
  }
}
