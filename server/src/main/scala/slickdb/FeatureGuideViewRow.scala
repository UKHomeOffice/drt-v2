package slickdb

import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import uk.gov.homeoffice.drt.db.AggregateDb

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class FeatureGuideViewRow(email: String, fileId: Int, viewTime: Timestamp)

class FeatureGuideView(tag: Tag) extends Table[FeatureGuideViewRow](tag, "feature_guide_view") {
  def email: Rep[String] = column[String]("email")

  def featureGuideId: Rep[Int] = column[Int]("file_id")

  def viewTime: Rep[Timestamp] = column[Timestamp]("view_time")

  def * : ProvenShape[FeatureGuideViewRow] = (email, featureGuideId, viewTime).mapTo[FeatureGuideViewRow]

  val pk = primaryKey("feature_guide_view_pkey", (email, featureGuideId))

}

trait FeatureGuideViewLike {
  def insertOrUpdate(fileId: Int, email: String)(implicit ec: ExecutionContext): Future[String]

  def featureViewed(email: String)(implicit ec: ExecutionContext): Future[Seq[String]]
}

case class FeatureGuideViewTable(tables: Tables) extends FeatureGuideViewLike {

  val userFeatureView = TableQuery[FeatureGuideView]

  def insertOrUpdate(fileId: Int, email: String)(implicit ec: ExecutionContext): Future[String] = {
    val insertOrUpdateAction = userFeatureView.insertOrUpdate(FeatureGuideViewRow(email, fileId, new Timestamp(System.currentTimeMillis())))
    tables.run(insertOrUpdateAction).map(_ => "success")
  }

  def featureViewed(email: String)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val selectAction = userFeatureView.filter(_.email === email).map(_.featureGuideId).result
    val fileViewed = tables.run(selectAction).map(_.map(_.toString))
    fileViewed
  }


}
