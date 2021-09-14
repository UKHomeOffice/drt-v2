package drt.shared.redlist

import drt.shared.SDateLike
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.Map

class RedListSpec extends Specification{
  val date20210807: SDateLike = SDate("2021-08-07T00:00", Crunch.europeLondonTimeZone)
  val date20210808: SDateLike = SDate("2021-08-08T00:00", Crunch.europeLondonTimeZone)
  val countriesRemovedFrom20210808 = Set("Bahrain", "India", "Qatar", "United Arab Emirates")
  val countriesAddedFrom20210808 = Set("Georgia", "Mayotte", "Mexico", "Reunion")

  "Given a date just before the red list changes on 08/08/2021" >> {
    val redList20210807 = RedListUpdates(RedList.redListChanges).countryCodesByName(date20210807.millisSinceEpoch)
    "The red list should contain India" >> {
      val expectedToExist = redList20210807.keys.toSet.intersect(countriesRemovedFrom20210808)
      val expectedToNotExist = redList20210807.keys.toSet.intersect(countriesAddedFrom20210808)

      expectedToExist === countriesRemovedFrom20210808 && expectedToNotExist === Set()
    }
  }

  "Given a date of the red list changes on 08/08/2021" >> {
    val redList = RedListUpdates(RedList.redListChanges).countryCodesByName(date20210808.millisSinceEpoch)
    "The red list should give the correct list as defined by redList20210808" >> {
      redList.keys === redList20210808.keys
    }
  }

  def redList20210808: Map[String, String] = Map(
    "Afghanistan" -> "AFG",
    "Angola" -> "AGO",
    "Argentina" -> "ARG",
    "Bangladesh" -> "BGD",
    "Bolivia" -> "BOL",
    "Botswana" -> "BWA",
    "Brazil" -> "BRA",
    "Burundi" -> "BDI",
    "Cape Verde" -> "CPV",
    "Chile" -> "CHL",
    "Colombia" -> "COL",
    "Costa Rica" -> "CRI",
    "Cuba" -> "CUB",
    "Congo (Kinshasa)" -> "COD",
    "Dominican Republic" -> "DOM",
    "Ecuador" -> "ECU",
    "Egypt" -> "EGY",
    "Eritrea" -> "ERI",
    "Eswatini" -> "SWZ",
    "Ethiopia" -> "ETH",
    "French Guiana" -> "GUF",
    "Georgia" -> "GEO",
    "Guyana" -> "GUY",
    "Haiti" -> "HTI",
    "Indonesia" -> "IDN",
    "Kenya" -> "KEN",
    "Lesotho" -> "LSO",
    "Malawi" -> "MWI",
    "Maldives" -> "MDV",
    "Mayotte" -> "MYT",
    "Mexico" -> "MEX",
    "Mongolia" -> "MNG",
    "Mozambique" -> "MOZ",
    "Myanmar" -> "MMR",
    "Namibia" -> "NAM",
    "Nepal" -> "NPL",
    "Oman" -> "OMN",
    "Pakistan" -> "PAK",
    "Panama" -> "PAN",
    "Paraguay" -> "PRY",
    "Peru" -> "PER",
    "Philippines" -> "PHL",
    "Reunion" -> "REU",
    "Rwanda" -> "RWA",
    "Seychelles" -> "SYC",
    "Sierra Leone" -> "SLE",
    "Somalia" -> "SOM",
    "South Africa" -> "ZAF",
    "Sri Lanka" -> "LKA",
    "Sudan" -> "SDN",
    "Suriname" -> "SUR",
    "Tanzania" -> "TZA",
    "Tunisia" -> "TUN",
    "Turkey" -> "TUR",
    "Trinidad and Tobago" -> "TTO",
    "Uganda" -> "UGA",
    "Uruguay" -> "URY",
    "Venezuela" -> "VEN",
    "Zambia" -> "ZMB",
    "Zimbabwe" -> "ZWE",
  )
}
