package slickdb

import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import uk.gov.homeoffice.drt.training.FeatureGuide

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}
import FeatureGuide.serializeToJsonString

case class FeatureGuideRow(id: Option[Int], uploadTime: Timestamp, fileName: Option[String], title: Option[String], markdownContent: String, published: Boolean)

class FeatureGuide(tag: Tag) extends Table[FeatureGuideRow](tag, "feature_guide") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def uploadTime: Rep[Timestamp] = column[Timestamp]("upload_time")

  def fileName: Rep[Option[String]] = column[Option[String]]("file_name")

  def title: Rep[Option[String]] = column[Option[String]]("title")

  def markdownContent: Rep[String] = column[String]("markdown_content")

  def published: Rep[Boolean] = column[Boolean]("published")

  def * : ProvenShape[FeatureGuideRow] =
    (id, uploadTime, fileName, title, markdownContent, published).mapTo[FeatureGuideRow]
}

trait FeatureGuideTableLike {
  def getAll()(implicit ec: ExecutionContext): Future[String]

  def selectAll(implicit ec: ExecutionContext): Future[Seq[FeatureGuideRow]]

  def getGuideIdForFilename(filename: String)(implicit ec: ExecutionContext): Future[Option[Int]]
}

case class FeatureGuideTable(table: Tables) extends FeatureGuideTableLike {

  val featureGuideTable = TableQuery[FeatureGuide]

  def getAll()(implicit ec: ExecutionContext): Future[String] = {
    selectAll.map(_.map(row =>
      FeatureGuide(row.id, row.uploadTime.getTime, row.fileName, row.title, row.markdownContent, row.published))).map(serializeToJsonString)
  }

  def selectAll(implicit ec: ExecutionContext): Future[Seq[FeatureGuideRow]] = {
    val selectAction = featureGuideTable.filter(_.published).sortBy(_.uploadTime.desc).result
    table.run(selectAction)

  }

  def getGuideIdForFilename(filename: String)(implicit ec: ExecutionContext): Future[Option[Int]] = {
    val selectAction = featureGuideTable.filter(_.fileName === filename).map(_.id).result
    val fileIds: Future[Seq[Option[Int]]] = table.run(selectAction)
    fileIds.map(_.headOption.flatten)
  }
}
