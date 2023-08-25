package slickdb

import drt.shared.Seminar
import org.joda.time.DateTime
import slick.lifted.ProvenShape
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class SeminarRow(id: Option[Int],
                      title: String,
                      description: String,
                      startTime: Timestamp,
                      endTime: Timestamp,
                      published: Boolean,
                      latestUpdateTime: Timestamp) {
  def toSeminar: Seminar = Seminar(id, title, description, startTime.getTime, endTime.getTime, published, latestUpdateTime.getTime)

  def getDate: String = new DateTime(startTime).toString("dd/MM/yyyy")

  def getStartTime: String = new DateTime(startTime).toString("HH:mm")

  def getEndTime: String = new DateTime(endTime).toString("HH:mm")
}

class Seminars(tag: Tag) extends Table[SeminarRow](tag, "seminar") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def title: Rep[String] = column[String]("title")

  def description: Rep[String] = column[String]("description")

  def startTime: Rep[Timestamp] = column[Timestamp]("start_time")

  def endTime: Rep[Timestamp] = column[Timestamp]("end_time")

  def published: Rep[Boolean] = column[Boolean]("published")

  def latestUpdateTime: Rep[Timestamp] = column[Timestamp]("latest_update_time")

  def * : ProvenShape[SeminarRow] = (id, title, description, startTime, endTime, published, latestUpdateTime).mapTo[SeminarRow]
}

trait SeminarTableLike {
  def updatePublishSeminar(seminarId: String, publish: Boolean)

  def updateSeminar(seminarRow: SeminarRow): Future[Int]

  def deleteSeminar(seminarId: String): Future[Int]

  def getSeminars(listAll: Boolean)(implicit ec: ExecutionContext): Future[String]

  def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]]

  def insertSeminarForm(title: String, description: String, startTime: Timestamp, endTime: Timestamp): Future[Int]
}

case class SeminarTable(tables: Tables) extends SeminarTableLike {
  val seminarTable = TableQuery[Seminars]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)

  def updatePublishSeminar(seminarId: String, publish: Boolean) = {
    val query = seminarTable.filter(_.id === seminarId.trim.toInt).map(f => (f.published, f.latestUpdateTime))
      .update(publish, getCurrentTime)
    tables.run(query)
  }

  def updateSeminar(seminarRow: SeminarRow): Future[Int] = seminarRow.id match {
    case Some(id) =>
      val query = seminarTable.filter(_.id === id).map(f => (f.title, f.description, f.startTime, f.endTime, f.latestUpdateTime))
        .update(seminarRow.title, seminarRow.description, seminarRow.startTime, seminarRow.endTime, getCurrentTime)
      tables.run(query)
    case None => Future.successful(0)
  }

  def deleteSeminar(seminarId: String): Future[Int] = {
    val query = seminarTable.filter(_.id === seminarId.trim.toInt).delete
    tables.run(query)
  }

  def getSeminars(listAll: Boolean)(implicit ec: ExecutionContext): Future[String] = {
    val query = if (listAll) seminarTable.sortBy(_.startTime).result
    else seminarTable.filter(_.startTime > new Timestamp(DateTime.now().withTimeAtStartOfDay().minusDays(1).getMillis)).sortBy(_.startTime).result
    val result = tables.run(query)
    result.map(rows => rows.map(_.toSeminar)).map(Seminar.serializeToJsonString)
  }

  def insertSeminarForm(title: String, description: String, startTime: Timestamp, endTime: Timestamp): Future[Int] = {
    val insertAction = seminarTable += SeminarRow(None, title, description, startTime, endTime, false, getCurrentTime)
    tables.run(insertAction)
  }

  override def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]] = {
    val intIds = ids.map(_.toInt).toSet
    val query = seminarTable.filter(_.id.inSet(intIds)).result
    tables.run(query)
  }
}
