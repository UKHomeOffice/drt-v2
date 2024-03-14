package services.graphstages

sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object LiveBaseArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

