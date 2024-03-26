package drt.client.components

import drt.client.services.JSDateConversions.SDate
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, ApiPaxTypeAndQueueCount, FeedSource, LiveBaseFeedSource, LiveFeedSource, PaxTypes, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

class ApiFeedStatusTest extends AnyWordSpec with Matchers {
  val paxFeedSourceOrder: List[FeedSource] = List(ApiFeedSource, LiveFeedSource)
  "ApiFeedStatus" should {
    val now: SDateLike = SDate("2022-05-31T12:00")
    val beforeNow = "2022-05-31T11:30"
    val afterNow = "2022-05-31T12:30"

    val landedWithNoSources = ArrivalGenerator.arrival(schDt = beforeNow, totalPax= Option(100), transPax = None, feedSource = LiveFeedSource)
    val landedWithNoPax = ArrivalGenerator.arrival(schDt = beforeNow, totalPax= None, transPax = None, feedSource = LiveBaseFeedSource)
    val landedWithLiveSource = ArrivalGenerator.arrival(schDt = beforeNow, totalPax= Option(100), transPax = None, feedSource = LiveFeedSource)
    val notLanded = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = afterNow, totalPax= Option(100), transPax = None, feedSource = LiveFeedSource), Set())
    val ctaLanded = ApiFlightWithSplits(landedWithLiveSource.copy(Origin = PortCode("ORK")), Set())
    val domesticLanded = ApiFlightWithSplits(landedWithLiveSource.copy(Origin = PortCode("EMA")), Set())
    val landedWithValidApi = ApiFlightWithSplits(landedWithNoSources,
      Set(Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, EeaDesk, 100d, None, None)), SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))))
    val landedWithInvalidApi = ApiFlightWithSplits(landedWithLiveSource,
      Set(Splits(Set(), SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))))
    val landedWithNoActPax = ApiFlightWithSplits(landedWithNoPax, Set(Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, EeaDesk, 100d, None, None)), SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC))))

    "given no flights, give None for stats and zero for total landed" in {
      val noFlights = Seq()
      val status = ApiFeedStatus(noFlights, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(None)
      status.validPct should ===(None)
      status.totalLanded should ===(0)
    }

    "given one flight with no live API which should have landed, give Option(0) for stats and 1 for total landed" in {
      val oneLandedWithNoAPI = Seq(ApiFlightWithSplits(landedWithNoSources, Set()))
      val status = ApiFeedStatus(oneLandedWithNoAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(0))
      status.validPct should ===(None)
      status.totalLanded should ===(1)
    }

    "given one flight with valid live API which should have landed, give Option(100) for both stats and 1 for total landed" in {
      val oneLandedWithValidAPI = Seq(landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithValidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }

    "given one flight with invalid live API which should have landed, give Option(100) for received and Option(0) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(landedWithInvalidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(0))
      status.totalLanded should ===(1)
    }

    "given two landed flights, one with with invalid live API and one with valid live API, give Option(100) for received and Option(50) for valid and 2 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(landedWithInvalidApi, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(50))
      status.totalLanded should ===(2)
    }

    "given two flights, one landed with with valid live API and one not landed, give Option(100) for received and Option(100) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(notLanded, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }

    "given two landed flights, one international with valid live API and one CTA, give Option(100) for received and Option(100) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(ctaLanded, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }

    "given two landed flights, one international with valid live API and one domestic, give Option(100) for received and Option(100) for valid and 1 for total landed" in {
      val oneLandedWithInvalidAPI = Seq(domesticLanded, landedWithValidApi)
      val status = ApiFeedStatus(oneLandedWithInvalidAPI, now.millisSinceEpoch, hasLiveFeed = true, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }

    "given one landed flight with no pax but live API where the port has no live feed, give Option(100) for received and None for valid and 1 for total landed" in {
      val status = ApiFeedStatus(Seq(landedWithNoActPax), now.millisSinceEpoch, hasLiveFeed = false, paxFeedSourceOrder)

      status.receivedPct should ===(Option(100))
      status.validPct should ===(Option(100))
      status.totalLanded should ===(1)
    }
  }
}
