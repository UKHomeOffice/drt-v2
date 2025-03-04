package drt.shared

case class Country(name: String, twoLetterCode: String, threeLetterCode: String, id: Int)

case class FlightHighlight(showNumberOfVisaNationals: Boolean,
                           showRequireAllSelected: Boolean,
                           showOnlyHighlightedRows: Boolean,
                           selectedAgeGroups: Seq[String],
                           selectedNationalities: Set[Country],
                           filterFlightSearch: String)
