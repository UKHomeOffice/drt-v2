package slickdb

import org.slf4j.{Logger, LoggerFactory}


case class VoyageManifestPassengerInfoTable(portCode: String, tables: Tables) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._
  import tables.VoyageManifestPassengerInfo

  val db: tables.profile.backend.DatabaseDef = Database.forConfig("aggregated-db")
  val voyageManifestPassengerInfoTableQuery = TableQuery[VoyageManifestPassengerInfo]
}
