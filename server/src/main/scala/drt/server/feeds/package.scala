package drt.server

import drt.shared.{ArrivalStatus, Operator}
import uk.gov.homeoffice.drt.ports.PortCode

package object feeds {
  import scala.language.implicitConversions

  object Implicits {
    implicit def portCodeFromString(str: String): PortCode = PortCode(str)
    implicit def maybeOperatorFromString(str: String): Option[Operator] = if (str.isEmpty) None else Option(Operator(str))
    implicit def statusFromString(str: String): ArrivalStatus = ArrivalStatus(str)
  }
}
