package slickdb

import actors.PostgresTables
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import upickle.default._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class UserFeatureView(email: String, fileId: Int, viewTime: Timestamp)

class UserFeatureViewTable(tag: Tag) extends Table[UserFeatureView](tag, "user_feature_view") {
  def email: Rep[String] = column[String]("email")

  def fileId: Rep[Int] = column[Int]("file_id")

  def viewTime: Rep[Timestamp] = column[Timestamp]("view_time")

  def * : ProvenShape[UserFeatureView] = (email, fileId, viewTime).mapTo[UserFeatureView]

  val pk = primaryKey("user_feature_view_pkey", (email, fileId))

}

sealed trait UserFeatureViewLike {

  import upickle.default.{ReadWriter, macroRW}

  implicit val timestampReader: ReadWriter[java.sql.Timestamp] = readwriter[String].bimap[java.sql.Timestamp](
    timestamp => timestamp.getTime.toString,
    str => new Timestamp(str.toLong)
  )
  implicit val rw: ReadWriter[UserFeatureView] = macroRW
}

object UserFeatureView extends UserFeatureViewLike {

  val userFeatureView = TableQuery[UserFeatureViewTable]

  def insertOrUpdate(fileId: Int, email: String)(implicit ec: ExecutionContext): Future[String] = {
    val insertOrUpdateAction = userFeatureView.insertOrUpdate(UserFeatureView(email, fileId, new Timestamp(System.currentTimeMillis())))
    PostgresTables.db.run(insertOrUpdateAction).map(_ => "success")
  }

  def fileViewedCount(email:String)(implicit ec: ExecutionContext): Future[Int] = {
    val selectAction = userFeatureView.filter(_.email === email).map(_.fileId).result
    val fileViewed = PostgresTables.db.run(selectAction).map(_.map(_.toString))
    fileViewed.map(_.length)
  }



}
