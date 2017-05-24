package drt.client

import diode.data.Pot
import drt.client.services.JSDateConversions.SDate

package object components {
  import diode.react.ReactPot
  import ReactPot._
  // expose jQuery under a more familiar name
//  val jQuery = JQueryStatic
  import scala.language.implicitConversions

  implicit def potReactForwarder[A](a: Pot[A]): potWithReact[A] = ReactPot.potWithReact(a)
  def makeDTReadable(dt: String): String = {
    if(dt != "") {
      val sdate = SDate.parse(dt)
      sdate.toLocalDateTimeString()
    } else ""
  }
}
