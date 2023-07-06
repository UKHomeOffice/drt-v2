package slickdb

import actors.PostgresTables
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import upickle.default._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class FeatureGuideView(email: String, fileId: Int, viewTime: Timestamp)

class FeatureGuideViewTable(tag: Tag) extends Table[FeatureGuideView](tag, "feature_guide_view") {
  def email: Rep[String] = column[String]("email")

  def featureGuideId: Rep[Int] = column[Int]("file_id")

  def viewTime: Rep[Timestamp] = column[Timestamp]("view_time")

  def * : ProvenShape[FeatureGuideView] = (email, featureGuideId, viewTime).mapTo[FeatureGuideView]

  val pk = primaryKey("feature_guide_view_pkey", (email, featureGuideId))

}

sealed trait FeatureGuideViewLike {

  import upickle.default.{ReadWriter, macroRW}

  implicit val timestampReader: ReadWriter[java.sql.Timestamp] = readwriter[String].bimap[java.sql.Timestamp](
    timestamp => timestamp.getTime.toString,
    str => new Timestamp(str.toLong)
  )
  implicit val rw: ReadWriter[FeatureGuideView] = macroRW
}

object FeatureGuideView extends FeatureGuideViewLike {

  val userFeatureView = TableQuery[FeatureGuideViewTable]

  def insertOrUpdate(fileId: Int, email: String)(implicit ec: ExecutionContext): Future[String] = {
    val insertOrUpdateAction = userFeatureView.insertOrUpdate(FeatureGuideView(email, fileId, new Timestamp(System.currentTimeMillis())))
    PostgresTables.db.run(insertOrUpdateAction).map(_ => "success")
  }

  def featureViewed(email: String)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val selectAction = userFeatureView.filter(_.email === email).map(_.featureGuideId).result
    val fileViewed = PostgresTables.db.run(selectAction).map(_.map(_.toString))
    fileViewed
  }


}
