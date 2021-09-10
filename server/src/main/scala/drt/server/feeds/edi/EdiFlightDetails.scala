package drt.server.feeds.edi

case class EdiFlightDetails(
                             //flightID: String,
                             TicketedOperator: String,//
                             AirlineCode_ICAO: String,//
                             AirlineCode_IATA: String,//
                             //aircraftTypeCode_ICAO: String,
                             //aircraftTypeCode_IATA: String,
                             //aircraftTypeDescription: String,
                             FlightNumber: String,//
                             //aircraftRegistration: String,
                             MAXPAX_Aircraft: Option[Int],//
                             AirportCode_IATA: String,//
                             DomsINtlCode: String,//
                             AirportEU_NonEU: String,//
                             ScheduledDateTime_Zulu: String,//
                             ArrDeptureCode: String,//
                             //flightTypeCode_ACCORD: String,
                             //flightTypeDescription: String,
                             //sector: String,
                             FlightStatus: Option[String],//
                             FlightCancelled: Int,//
                             EstimatedDateTime_Zulu: Option[String],//
                             ActualDateTime_Zulu: Option[String],//
                             ZoningDateTime_Zulu: Option[String],//
                             ChocksDateTime_Zulu: Option[String],//
                             //boardingStartDateTime_Zulu: Option[String],
                             //boardingEndDateTime_Zulu: Option[String],
                             //firstBagDateTime_Zulu: String,
                             //checkDesk_From: Option[String],
                             //checkDesk_To: Option[String],
                             DepartureGate: Option[String],//
                             StandCode: Option[String],
                             //standDescription: String,
                             //remoteStand: String,
                             GateName: Option[String],//
                             //gateAction: Option[String],
                             //gateChange: Option[String],
                             BagageReclaim: Option[String],//
                             TerminalCode: String,//
                             RunWayCode: Option[String],
                             //codeShares: Option[String],
                             //turnaroundFlightID: Int
                           )


case class EdiFlightDetailsList(ediFeeds: List[EdiFlightDetails])