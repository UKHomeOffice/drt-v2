package serialization

import controllers.application.RedListJsonFormats
import drt.shared.PortCode
import org.specs2.mutable.Specification
import spray.json._
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, RedListUpdates, SetRedListUpdate}


case class WithAMap(things: Map[String, String])

class SprayJsonSerialisationSpec extends Specification {
  "Stuff" >> {
    import spray.json._

    implicit val rd = RedListJsonFormats.redListUpdateJsonFormat
    implicit val rds = RedListJsonFormats.redListUpdatesJsonFormat

    val update = RedListUpdate(1613347200000L, Map("France" -> "FRA"), List("Germany"))
    val updateJson = update.toJson

    val updates: RedListUpdates = RedListUpdates(
      Map(1613347200000L -> update)
    )
    val jsonStr = updates.toJson

    println(jsonStr)
    success
  }
}
