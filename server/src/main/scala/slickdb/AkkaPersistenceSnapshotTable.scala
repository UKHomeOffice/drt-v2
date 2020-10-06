package slickdb


case class AkkaPersistenceSnapshotTable(tables: Tables) {
  import tables.profile.api._

  val db: tables.profile.backend.DatabaseDef = Database.forConfig("slick.db")
}
