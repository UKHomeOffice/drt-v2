package passengersplits.core

object PassengerTypeCalculatorValues {

  object CountryCodes {
    val Austria = "AUT"
    val Belgium = "BEL"
    val Bulgaria = "BGR"
    val Croatia = "HRV"
    val Cyprus = "CYP"
    val Czech = "CZE"
    val Denmark = "DNK"
    val Estonia = "EST"
    val Finland = "FIN"
    val France = "FRA"
    val Germany = "DEU"
    val Greece = "GRC"
    val Hungary = "HUN"
    val Iceland = "ISL"
    val Ireland = "IRL"
    val Italy = "ITA"
    val Latvia = "LVA"
    val Liechtenstein = "LIE"
    val Norway = "NOR"
    val Lithuania = "LTU"
    val Luxembourg = "LUX"
    val Malta = "MLT"
    val Netherlands = "NLD"
    val Poland = "POL"
    val Portugal = "PRT"
    val Romania = "ROU"
    val Slovakia = "SVK"
    val Slovenia = "SVN"
    val Spain = "ESP"
    val Sweden = "SWE"
    val Switzerland = "CHE"
    val UK = "GBR"

    val Australia = "AUS"
    val Canada = "CAN"
    val Japan = "JPN"
    val NewZealand = "NZL"
    val Singapore = "SGP"
    val SouthKorea = "KOR"
    val USA = "USA"
  }

  import CountryCodes._

  lazy val EUCountries: Set[String] = {
    Set(
      Austria,
      Belgium,
      Bulgaria,
      Croatia,
      Cyprus,
      Czech,
      Denmark,
      Estonia,
      Finland,
      France,
      Germany,
      Greece,
      Hungary,
      Ireland,
      Italy,
      Latvia,
      Lithuania,
      Luxembourg,
      Malta,
      Netherlands,
      Poland,
      Portugal,
      Romania,
      Slovakia,
      Slovenia,
      Spain,
      Sweden,
      UK
    )
  }

  lazy val EEACountries: Set[String] = {
    val extras = Set(Iceland, Norway, Liechtenstein, Switzerland)
    EUCountries ++ extras
  }

  lazy val B5JPlusCountries: Set[String] = Set(
    USA,
    Australia,
    Canada,
    NewZealand,
    Japan,
    SouthKorea,
    Singapore
  )


  object DocType {
    val Visa = "V"
    val Passport = "P"

    def apply(docTypeCode: String) = docTypeCode.toUpperCase() match {
      case "V" => Visa
      case "P" | "PASSPORT" => Passport
      case _ => docTypeCode
    }
  }

  val nonMachineReadableCountries = Set(Italy, Greece, Slovakia, Portugal)

  val EEA = "EEA"
}
