package actors.serializers

import drt.shared.Alert
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate

class AlertMessageConversionSpec extends Specification{

  "Given an alert I should serialise it and deserialise it and get back the same result" >> {
    val created = SDate("2020-01-11T13:00").millisSinceEpoch
    val expiry = SDate("2020-01-11T14:00").millisSinceEpoch
    val alert = Alert("title", "message", "warning", expiry, created)

    val serialised = AlertMessageConversion.alertToMessage(alert)
    val deserialised = AlertMessageConversion.alertFromMessage(serialised).get

    deserialised === alert
  }

}
