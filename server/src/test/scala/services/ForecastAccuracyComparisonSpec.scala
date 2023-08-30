package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate


class ForecastAccuracyComparisonSpec extends Specification {
  def ua(flightNumber: Int): UniqueArrival = UniqueArrival(flightNumber, T1, SDate("2023-07-24T12:00").millisSinceEpoch, PortCode("JFK"))

  "Given one actual arrival and a matching forecast arrival the coverage should be 100%" >> {
    ForecastAccuracyComparison.percentageForecastOfActuals(Set(ua(1)), Set(ua(1))) === 1.0
  }
  "Given two actual arrivals and only one matching forecast arrival the coverage should be 50%" >> {
    ForecastAccuracyComparison.percentageForecastOfActuals(Set(ua(1), ua(2)), Set(ua(1))) === 0.5
  }
  "Given one actual arrival and 2 forecast arrivals (with one matching) the coverage should be 100%" >> {
    ForecastAccuracyComparison.percentageForecastOfActuals(Set(ua(1)), Set(ua(1), ua(2))) === 1.0
  }

  val actuals: Map[UniqueArrival, Int] = Map(
    ua(1) -> 100,
    ua(2) -> 150,
    ua(3) -> 90,
  )

  "Given flights where the forecast matches with the actuals, the average flight error should be 0%" >> {
    ForecastAccuracyComparison.maybeAverageFlightError(actuals, actuals, 0.5) === Option(0.0)
  }

  "Given flights where the forecast matches with the actuals, the absolute error should be 0%" >> {
    ForecastAccuracyComparison.maybeAbsoluteError(actuals, actuals, 0.5) === Option(0.0)
  }

  "Given flights where the forecast was 10% above the actuals for each flight, the average flight error should be 10%" >> {
    val forecasts = Map(
      ua(1) -> 110,
      ua(2) -> 165,
      ua(3) -> 99,
    )
    ForecastAccuracyComparison.maybeAverageFlightError(actuals, forecasts, 0.5) === Option(0.1)
  }

  "Given flights where the forecast was 10% above the actuals for each flight, the absolute error should be 10%" >> {
    val forecasts = Map(
      ua(1) -> 110,
      ua(2) -> 165,
      ua(3) -> 99,
    )
    ForecastAccuracyComparison.maybeAbsoluteError(actuals, forecasts, 0.5) === Option(0.1)
  }

  "Given flights where the forecast was 10% above the actuals for each flight, the absolute error should be 10%" >> {
    val forecasts = Map(
      ua(1) -> 90,
      ua(2) -> 135,
      ua(3) -> 81,
    )
    ForecastAccuracyComparison.maybeAbsoluteError(actuals, forecasts, 0.5) === Option(-0.1)
  }

  "Given flights where the forecast was 10%, 20% and 30% above or below the actuals for each flight, the average flight error should be 20%" >> {
    val forecasts = Map(
      ua(1) -> 110,
      ua(2) -> 120,
      ua(3) -> 117,
    )
    ForecastAccuracyComparison.maybeAverageFlightError(actuals, forecasts, 0.5) === Option(0.2)
  }

  "Given flights where there is only 33% coverage and a minimum coverage set at 34% then we should get None" >> {
    val forecasts = Map(
      ua(1) -> 110,
    )
    ForecastAccuracyComparison.maybeAverageFlightError(actuals, forecasts, 0.34) === None
  }
}
