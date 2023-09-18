package slickdb

import drt.shared.Seminar
import org.joda.time.DateTime
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape

import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future}

case class SeminarRow(id: Option[Int],
                      title: String,
                      startTime: Timestamp,
                      endTime: Timestamp,
                      isPublished: Boolean,
                      meetingLink: Option[String],
                      lastUpdatedAt: Timestamp) {
  def toSeminar: Seminar = Seminar(id, title, startTime.getTime, endTime.getTime, isPublished, meetingLink, lastUpdatedAt.getTime)

  def getDate: String = getUKStringDate(startTime, dateFormatter)

  def getStartTime: String = getUKStringDate(startTime, timeFormatter)

  def getEndTime: String = getUKStringDate(endTime, timeFormatter)

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

  val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  def getUKStringDate(timestamp: Timestamp, formatter: DateTimeFormatter): String = zonedUKDateTime(timestamp).format(formatter)

  val zonedUKDateTime: Timestamp => ZonedDateTime = timestamp => timestamp.toInstant.atZone(ZoneId.of("Europe/London"))

}

class Seminars(tag: Tag) extends Table[SeminarRow](tag, "seminar") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def title: Rep[String] = column[String]("title")

  def startTime: Rep[Timestamp] = column[Timestamp]("start_time")

  def endTime: Rep[Timestamp] = column[Timestamp]("end_time")

  def isPublished: Rep[Boolean] = column[Boolean]("is_published")

  def meetingLink: Rep[Option[String]] = column[Option[String]]("meeting_link")

  def lastUpdatedAt: Rep[Timestamp] = column[Timestamp]("last_updated_at")

  def * : ProvenShape[SeminarRow] = (id, title, startTime, endTime, isPublished, meetingLink, lastUpdatedAt).mapTo[SeminarRow]
}

trait SeminarTableLike {
  def updatePublishSeminar(seminarId: String, publish: Boolean): Future[Int]

  def updateSeminar(seminarRow: SeminarRow): Future[Int]

  def deleteSeminar(seminarId: String): Future[Int]

  def getFuturePublishedSeminars()(implicit ec: ExecutionContext): Future[Seq[Seminar]]

  def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]]
}

case class SeminarTable(tables: Tables) extends SeminarTableLike {
  val seminarTable = TableQuery[Seminars]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)

  def updatePublishSeminar(seminarId: String, publish: Boolean): Future[Int] = {
    val query = seminarTable.filter(_.id === seminarId.trim.toInt).map(f => (f.isPublished, f.lastUpdatedAt))
      .update(publish, getCurrentTime)
    tables.run(query)
  }

  def updateSeminar(seminarRow: SeminarRow): Future[Int] = seminarRow.id match {
    case Some(id) =>
      val query = seminarTable.filter(_.id === id).map(f => (f.title, f.startTime, f.endTime, f.lastUpdatedAt))
        .update(seminarRow.title, seminarRow.startTime, seminarRow.endTime, getCurrentTime)
      tables.run(query)
    case None => Future.successful(0)
  }

  def deleteSeminar(seminarId: String): Future[Int] = {
    val query = seminarTable.filter(_.id === seminarId.trim.toInt).delete
    tables.run(query)
  }

  def getFuturePublishedSeminars()(implicit ec: ExecutionContext): Future[Seq[Seminar]] = {
    val query = seminarTable
      .filter(r => r.isPublished && r.startTime > new Timestamp(DateTime.now().withTimeAtStartOfDay().minusDays(1).getMillis))
      .sortBy(_.startTime)
      .result
    val result = tables.run(query)
    result.map(rows => rows.map(_.toSeminar))
  }

  override def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]] = {
    val intIds = ids.map(_.toInt).toSet
    val query = seminarTable.filter(_.id.inSet(intIds)).result
    tables.run(query)
  }
}
