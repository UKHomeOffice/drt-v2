package actors.serializers

import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import services.SDate
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, SetRedListUpdate}

class RedListUpdatesMessageConversionSpec extends Specification {
  val effectiveFrom: MillisSinceEpoch = SDate("2021-09-23T12:15:00").millisSinceEpoch
  val additions = Map("France" -> "FRA")
  val removals = List("France")
  val setRedListUpdate: SetRedListUpdate = SetRedListUpdate(effectiveFrom, RedListUpdate(effectiveFrom, additions, removals))

  "A RedListUpdatesMessageConversion" >> {
    "Should be able to serialise and deserialise a SetRedListUpdate" >> {
      val serialised = RedListUpdatesMessageConversion.setUpdatesToMessage(setRedListUpdate)
      val deserialised = RedListUpdatesMessageConversion.setUpdatesFromMessage(serialised)

      deserialised.get === setRedListUpdate
    }
  }
}
