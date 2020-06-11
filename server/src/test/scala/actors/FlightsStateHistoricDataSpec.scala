package actors

import drt.shared.SDateLike
import org.specs2.mutable.Specification
import services.SDate


class FlightsStateHistoricDataSpec extends Specification {
  val cutoff: SDateLike = SDate("2020-06-11T12:00Z")
  val pointInTimeNonLegacy: SDateLike = SDate("2020-06-11T12:00Z")
  val pointInTimeLegacy: SDateLike = SDate("2020-06-11T11:59Z")

  s"Given a cutoff of ${cutoff.toISOString()}" >> {
    s"When I ask if non-legacy date ${pointInTimeNonLegacy.toISOString()} is a non-legacy date" >> {
      "I should get true" >> {
        val isNonLegacy: Boolean = FlightsStateActor.isNonLegacyRequest(pointInTimeNonLegacy, cutoff)
        isNonLegacy === true
      }
    }
    s"When I ask if a legacy date ${pointInTimeLegacy.toISOString()} is a non-legacy date" >> {
      "I should get false" >> {
        val isNonLegacy: Boolean = FlightsStateActor.isNonLegacyRequest(pointInTimeLegacy, cutoff)
        isNonLegacy === false
      }
    }
  }
}
