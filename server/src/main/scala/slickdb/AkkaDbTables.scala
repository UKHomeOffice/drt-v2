
package slickdb

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.PostgresProfile

import scala.concurrent.Future


case class JournalRow(ordering: Long,
                      persistenceId: String,
                      sequenceNumber: Long,
                      deleted: Option[Boolean],
                      tags: Option[String],
                      message: Array[Byte],
                     )

case class SnapshotRow(persistenceId: String,
                       sequenceNumber: Long,
                       created: Long,
                       snapshot: Array[Byte],
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

  class JournalTable(_tableTag: Tag) extends Table[JournalRow](_tableTag, maybeSchema, "processed_json") {
    val ordering: Rep[Long] = column[Long]("ordering")
    val persistenceId: Rep[String] = column[String]("persistence_id", O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long]("sequence_number")
    val deleted: Rep[Option[Boolean]] = column[Option[Boolean]]("deleted")
    val tags: Rep[Option[String]] = column[Option[String]]("tags")
    val message: Rep[Array[Byte]] = column[Array[Byte]]("message")

    def * = (ordering, persistenceId, sequenceNumber, deleted, tags, message).mapTo[JournalRow]
  }

  class SnapshotTable(_tableTag: Tag) extends Table[SnapshotRow](_tableTag, maybeSchema, "snapshot") {
    val persistenceId: Rep[String] = column[String]("persistence_id", O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long]("sequence_number")
    val created: Rep[Long] = column[Long]("created")
    val snapshot: Rep[Array[Byte]] = column[Array[Byte]]("snapshot")

    def * = (persistenceId, sequenceNumber, created, snapshot).mapTo[SnapshotRow]
  }

  /** Collection-like TableQuery object for table VoyageManifestPassengerInfo */
  lazy val journalTable = new TableQuery(tag => new JournalTable(tag))
  lazy val snapshotTable = new TableQuery(tag => new SnapshotTable(tag))

  lazy val schema: profile.SchemaDescription = journalTable.schema ++ snapshotTable.schema
}
