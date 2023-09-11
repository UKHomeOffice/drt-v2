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
                      description: String,
                      startTime: Timestamp,
                      endTime: Timestamp,
                      published: Boolean,
                      meetingLink: Option[String],
                      latestUpdateTime: Timestamp) {
  def toSeminar: Seminar = Seminar(id, title, description, startTime.getTime, endTime.getTime, published, meetingLink, latestUpdateTime.getTime)

  def getDate: String = getUKStringDate(startTime, dateFormatter)

  def getStartTime: String = getUKStringDate(startTime, timeFormatter)

  def getEndTime: String = getUKStringDate(endTime, timeFormatter)

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

  val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  def getUKStringDate(timestamp: Timestamp, formatter: DateTimeFormatter): String = zonedUKDateTime(timestamp).format(formatter)

  val zonedUKDateTime: Timestamp => ZonedDateTime = timestamp => timestamp.toInstant.atZone(ZoneId.of("Europe/London"))

  def getUKStartTime: ZonedDateTime = zonedUKDateTime(startTime)

  def getUKEndTime: ZonedDateTime = zonedUKDateTime(endTime)
}

class Seminars(tag: Tag) extends Table[SeminarRow](tag, "seminar") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def title: Rep[String] = column[String]("title")

  def description: Rep[String] = column[String]("description")

  def startTime: Rep[Timestamp] = column[Timestamp]("start_time")

  def endTime: Rep[Timestamp] = column[Timestamp]("end_time")

  def published: Rep[Boolean] = column[Boolean]("published")

  def meetingLink: Rep[Option[String]] = column[Option[String]]("meeting_link")

  def latestUpdateTime: Rep[Timestamp] = column[Timestamp]("latest_update_time")

  def * : ProvenShape[SeminarRow] = (id, title, description, startTime, endTime, published, meetingLink, latestUpdateTime).mapTo[SeminarRow]
}

trait SeminarTableLike {
  def updatePublishSeminar(seminarId: String, publish: Boolean): Future[Int]

  def updateSeminar(seminarRow: SeminarRow): Future[Int]

  def deleteSeminar(seminarId: String): Future[Int]

  def getPublishedSeminars(listAll: Boolean)(implicit ec: ExecutionContext): Future[String]

  def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]]
}

case class SeminarTable(tables: Tables) extends SeminarTableLike {
  val seminarTable = TableQuery[Seminars]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)

  def updatePublishSeminar(seminarId: String, publish: Boolean): Future[Int] = {
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

  def getPublishedSeminars(listAll: Boolean)(implicit ec: ExecutionContext): Future[String] = {
    val query = if (listAll) seminarTable.filter(_.published).sortBy(_.startTime).result
    else seminarTable.filter(r => r.published && r.startTime > new Timestamp(DateTime.now().withTimeAtStartOfDay().minusDays(1).getMillis)).sortBy(_.startTime).result
    val result = tables.run(query)
    result.map(rows => rows.map(_.toSeminar)).map(Seminar.serializeToJsonString)
  }

  override def getSeminars(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[SeminarRow]] = {
    val intIds = ids.map(_.toInt).toSet
    val query = seminarTable.filter(_.id.inSet(intIds)).result
    tables.run(query)
  }
}
