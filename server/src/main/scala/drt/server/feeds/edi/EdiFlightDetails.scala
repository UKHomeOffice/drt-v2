package drt.server.feeds.edi

case class EdiFlightDetails(
                             TicketedOperator: String,
                             AirlineCode_ICAO: String,
                             AirlineCode_IATA: String,
                             FlightNumber: String,
                             MAXPAX_Aircraft: Option[Int],
                             AirportCode_IATA: String,
                             DomsINtlCode: String,
                             AirportEU_NonEU: String,
                             ScheduledDateTime_Zulu: String,
                             ArrDeptureCode: String,
                             FlightStatus: Option[String],
                             Passengers: Option[Int],
                             EstimatedDateTime_Zulu: Option[String],
                             ActualDateTime_Zulu: Option[String],
                             ZoningDateTime_Zulu: Option[String],
                             ChocksDateTime_Zulu: Option[String],
                             DepartureGate: Option[String],
                             StandCode: Option[String],
                             GateName: Option[String],
                             BagageReclaim: Option[String],
                             TerminalCode: String,
                             RunWayCode: Option[String],
                           )


case class EdiFlightDetailsList(ediFeeds: List[EdiFlightDetails])