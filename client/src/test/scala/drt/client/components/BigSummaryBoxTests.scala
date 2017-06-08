package drt.client.components

import diode.data.Ready
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel
import drt.shared.{ApiFlight, ApiFlightWithSplits, SDateLike}
import drt.shared.FlightsApi.FlightsWithSplits
import japgolly.scalajs.react.{test, _}
import japgolly.scalajs.react.test._
import japgolly.scalajs.react.vdom.html_<^._
import utest.TestSuite
import utest._


object BigSummaryBoxTests extends TestSuite {

  import BigSummaryBoxed._

  test.WebpackRequire.ReactTestUtils

  def tests = TestSuite {
    "Summary for the next 3 hours" - {
      "Given a rootModel with flightsWithSplits with flights arriving 2017-05-01T12:01Z onwards" - {
        "Given 0 flights" - {
          val rootModel = RootModel(flightsWithSplitsPot = Ready(FlightsWithSplits(Nil)))

          "AND a current time of 2017-05-01T12:00" - {
            val now = SDate(2016, 5, 1, 12, 0)
            val nowPlus3Hours = now.addHours(3)

            "Then we can get a number of flights arriving in that period" - {
              val countOfFlights = rootModel.flightsWithSplitsPot.map(_.flights.filter(f => {
                val flightDt = SDate.parse(f.apiFlight.SchDT)
                now.millisSinceEpoch <= flightDt.millisSinceEpoch && flightDt.millisSinceEpoch <= nowPlus3Hours.millisSinceEpoch
              }).length)
              assert(countOfFlights == Ready(0))
            }
          }
        }
        "Given 3 flights" - {
          import ApiFlightGenerator._

          val apiFlight1 = apiFlight("2017-05-01T12:05Z", FlightID = 1, ActPax = 200)
          val apiFlight2 = apiFlight("2017-05-01T13:05Z", FlightID = 2, ActPax = 300)
          val apiFlight3 = apiFlight("2017-05-01T13:20Z", FlightID = 3, ActPax = 40)

          val rootModel = RootModel(flightsWithSplitsPot = Ready(FlightsWithSplits(
            ApiFlightWithSplits(apiFlight1, Nil) ::
              ApiFlightWithSplits(apiFlight2, Nil) ::
              ApiFlightWithSplits(apiFlight3, Nil) :: Nil)))

          "AND a current time of 2017-05-01T12:00" - {
            val now = SDate(2017, 5, 1, 12, 0)
            val nowPlus3Hours = now.addHours(3)

            "Then we can get a number of flights arriving in that period" - {
              val countOfFlights = countFlightsInPeriod(rootModel, now, nowPlus3Hours)
              val expected = Ready(3)
              assert(countOfFlights == expected)
            }
            "And we can get the total pax to the PCP" - {
              val countOfPax = countPaxInPeriod(rootModel, now, nowPlus3Hours)
              assert(countOfPax == Ready(200 + 300 + 40))
            }
          }
        }
      }
    }
  }


}

object ApiFlightGenerator {
  def apiFlight(
                 SchDT: String,
                 Operator: String = "",
                 Status: String = "",
                 EstDT: String = "",
                 ActDT: String = "",
                 EstChoxDT: String = "",
                 ActChoxDT: String = "",
                 Gate: String = "",
                 Stand: String = "",
                 MaxPax: Int = 1,
                 ActPax: Int = 0,
                 TranPax: Int = 0,
                 RunwayID: String = "",
                 BaggageReclaimId: String = "",
                 FlightID: Int = 2,
                 AirportID: String = "STN",
                 Terminal: String = "1",
                 rawICAO: String = "",
                 iataFlightCode: String = "BA123",
                 Origin: String = "",
                 PcpTime: Long = 0): ApiFlight =
    ApiFlight(
      Operator = Operator,
      Status = Status,
      EstDT = EstDT,
      ActDT = ActDT,
      EstChoxDT = EstChoxDT,
      ActChoxDT = ActChoxDT,
      Gate = Gate,
      Stand = Stand,
      MaxPax = MaxPax,
      ActPax = ActPax,
      TranPax = TranPax,
      RunwayID = RunwayID,
      BaggageReclaimId = BaggageReclaimId,
      FlightID = FlightID,
      AirportID = AirportID,
      Terminal = Terminal,
      rawICAO = rawICAO,
      rawIATA = iataFlightCode,
      Origin = Origin,
      PcpTime = PcpTime,
      SchDT = SchDT
    )

}