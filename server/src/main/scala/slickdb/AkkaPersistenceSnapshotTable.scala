package slickdb


case class AkkaPersistenceSnapshotTable(tables: AggregatedDbTables) {
  import tables.profile.api._

  val db: tables.profile.backend.DatabaseDef = Database.forConfig("slick.db")
}
