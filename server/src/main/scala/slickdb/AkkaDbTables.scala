
package slickdb

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.PostgresProfile

import scala.concurrent.Future


case class JournalRow(ordering: Long,
                      persistenceId: String,
                      sequenceNumber: Long,
                     )

case class SnapshotRow(persistenceId: String,
                       sequenceNumber: Long,
                       created: Long,
                      )

/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
trait AkkaDbTables {
  val profile: slick.jdbc.JdbcProfile

  def run[R](a: DBIOAction[R, NoStream, Nothing]): Future[R]

  import profile.api._

  private val maybeSchema = profile match {
    case _: PostgresProfile =>
      Some("public")
    case _ =>
      None
  }

  class JournalTable(_tableTag: Tag) extends Table[JournalRow](_tableTag, maybeSchema, "journal") {
    val ordering: Rep[Long] = column[Long]("ordering")
    val persistenceId: Rep[String] = column[String]("persistence_id", O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long]("sequence_number")

    def * = (ordering, persistenceId, sequenceNumber).mapTo[JournalRow]
  }

  class SnapshotTable(_tableTag: Tag) extends Table[SnapshotRow](_tableTag, maybeSchema, "snapshot") {
    val persistenceId: Rep[String] = column[String]("persistence_id", O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long]("sequence_number")
    val created: Rep[Long] = column[Long]("created")

    def * = (persistenceId, sequenceNumber, created).mapTo[SnapshotRow]
  }

  /** Collection-like TableQuery object for table VoyageManifestPassengerInfo */
  lazy val journalTable = new TableQuery(tag => new JournalTable(tag))
  lazy val snapshotTable = new TableQuery(tag => new SnapshotTable(tag))

  lazy val schema: profile.SchemaDescription = journalTable.schema ++ snapshotTable.schema
}
