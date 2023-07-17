package slickdb

import actors.PostgresTables
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import upickle.default.{macroRW, write}

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

import upickle.default._

case class FeatureGuideRow(id: Option[Int], uploadTime: Timestamp, fileName: Option[String], title: Option[String], markdownContent: String, published: Boolean)

class FeatureGuideTable(tag: Tag) extends Table[FeatureGuideRow](tag, "feature_guide") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def uploadTime: Rep[Timestamp] = column[Timestamp]("upload_time")

  def fileName: Rep[Option[String]] = column[Option[String]]("file_name")

  def title: Rep[Option[String]] = column[Option[String]]("title")

  def markdownContent: Rep[String] = column[String]("markdown_content")

  def published: Rep[Boolean] = column[Boolean]("published")

  def * : ProvenShape[FeatureGuideRow] =
    (id, uploadTime, fileName, title, markdownContent, published).mapTo[FeatureGuideRow]
}

sealed trait FeatureGuideLike {

  import upickle.default.{ReadWriter, macroRW}

  implicit val timestampReader: ReadWriter[java.sql.Timestamp] = readwriter[String].bimap[java.sql.Timestamp](
    timestamp => timestamp.getTime.toString,
    str => new Timestamp(str.toLong)
  )
  implicit val rw: ReadWriter[FeatureGuideRow] = macroRW
}

object FeatureGuideRow extends FeatureGuideLike {

  val featureGuideTable = TableQuery[FeatureGuideTable]

  def getAll()(implicit ec: ExecutionContext): Future[String] = {
    selectAll.map(write(_))
  }

  def selectAll: Future[Seq[FeatureGuideRow]] = {
    val selectAction = featureGuideTable.filter(_.published).sortBy(_.uploadTime.desc).result
    PostgresTables.db.run(selectAction)
  }

  def getGuideIdForFilename(filename: String)(implicit ec: ExecutionContext): Future[Option[Int]] = {
    val selectAction = featureGuideTable.filter(_.fileName === filename).map(_.id).result
    val fileIds: Future[Seq[Option[Int]]] = PostgresTables.db.run(selectAction)
    fileIds.map(_.headOption.flatten)
  }
}
