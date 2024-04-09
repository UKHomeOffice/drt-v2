package actors.daily

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.protobuf.messages.PaxMessage.PaxCountMessage
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

class PassengersActorSpec extends Specification {
  val now: () => SDateLike = () => SDate("2020-06-15T00:00", europeLondonTimeZone)
  val validDate: SDateLike = now().addDays(-1)
  val validDate2: SDateLike = now().addDays(-2)
  val inValidDate: SDateLike = now().addDays(-3)
  val daysInAverage = 2

  s"Given a 2 days for the average, and a now of 15 June, ${now().toISOString}, the days taken into account should be 13th and 14th" >> {
    s"When I ask if 14th June (${validDate.toISOString}) is relevant" >> {
      "I should see it is relevant" >> {
        val pcm = PaxCountMessage(Option(validDate.millisSinceEpoch), Option(validDate.millisSinceEpoch), Option(1))
        val validMessages = PassengersActor.relevantPaxCounts(daysInAverage, now)(Seq(pcm))

        validMessages.contains(pcm)
      }
    }
  }

  s"When I ask if 13th June (${validDate2.toISOString}) is relevant" >> {
    "I should see it is relevant" >> {
      val pcm = PaxCountMessage(Option(validDate2.millisSinceEpoch), Option(validDate2.millisSinceEpoch), Option(1))
      val validMessages = PassengersActor.relevantPaxCounts(daysInAverage, now)(Seq(pcm))

      validMessages.contains(pcm)
    }
  }

  s"When I ask if 12th June (${inValidDate.toISOString}) is relevant" >> {
    "I should see it is not relevant" >> {
      val pcm = PaxCountMessage(Option(inValidDate.millisSinceEpoch), Option(inValidDate.millisSinceEpoch), Option(1))
      val validMessages = PassengersActor.relevantPaxCounts(daysInAverage, now)(Seq(pcm))

      !validMessages.contains(pcm)
    }
  }
}
