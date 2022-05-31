package drt.client.components

import drt.client.services.JSDateConversions.SDate
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.time.SDateLike

class ApiFeedStatusTest extends AnyWordSpec with Matchers {
  "ApiFeedStatus" should {
    val now: SDateLike = SDate("2022-05-31T12:00")
    val beforeNow = "2022-05-31T11:30"
    val afterNow = "2022-05-31T12:30"
    val timeToChox = 0
    val considerPredictions = true

    val landedWithNoSources = ArrivalGenerator.apiFlight(schDt = beforeNow, actPax = Option(100))
    val landedWithLiveSource = ArrivalGenerator.apiFlight(schDt = beforeNow, actPax = Option(100), feedSources = Set(LiveFeedSource))
    val notLanded = ApiFlightWithSplits(ArrivalGenerator.apiFlight(schDt = afterNow, actPax = Option(100)), Set())
    val landedWithValidApi = ApiFlightWithSplits(landedWithNoSources, Set(Splits(Set(), SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))))
    val landedWithInvalidApi = ApiFlightWithSplits(landedWithLiveSource, Set(Splits(Set(), SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))))

    "given no flights, give None for stats and zero for total landed" in {
      val noFlights = Seq()
      val status = ApiFeedStatus(noFlights, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(None)
      status.validPct should ===(None)
      status.totalLanded should ===(0)
    }

    "given one flight with no live API which should have landed, give Option(0) for stats and 1 for total landed" in {
      val oneLandedWithNoAPI = Seq(ApiFlightWithSplits(landedWithNoSources, Set()))
      val status = ApiFeedStatus(oneLandedWithNoAPI, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(Option(0))
      status.validPct should ===(None)
      status.totalLanded should ===(1)
    }

    "given one flight with valid live API which should have landed, give Option(100) for both stats and 1 for total landed" in {
      val oneLandedWithValidAPI = Seq(landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithValidAPI, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }

    "given one flight with invalid live API which should have landed, give Option(100) for received and Option(0) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(landedWithInvalidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(0))
      status.totalLanded should ===(1)
    }

    "given two landed flights, one with with invalid live API and one with valid live API, give Option(100) for received and Option(50) for valid and 2 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(landedWithInvalidApi, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(50))
      status.totalLanded should ===(2)
    }

    "given two flights, one landed with with valid live API and one not landed, give Option(100) for received and Option(100) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(notLanded, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, timeToChox, considerPredictions)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }
  }
}
