package drt.shared

case class Country(name: String, twoLetterCode: String, threeLetterCode: String, id: Int)

case class FlightHighlight(showNumberOfVisaNationals: Boolean,
                           showRequireAllSelected: Boolean,
                           showHighlightedRows: Boolean,
                           selectedAgeGroups: Seq[String],
                           selectedNationalities: Set[Country],
                           flightNumber: String)
