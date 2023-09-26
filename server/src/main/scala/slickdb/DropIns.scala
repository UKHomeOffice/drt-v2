package slickdb

import drt.shared.DropIn
import org.joda.time.DateTime
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape

import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future}

case class DropInRow(id: Option[Int],
                     title: String,
                     startTime: Timestamp,
                     endTime: Timestamp,
                     isPublished: Boolean,
                     meetingLink: Option[String],
                     lastUpdatedAt: Timestamp) {
  def toDropIn: DropIn = DropIn(id, title, startTime.getTime, endTime.getTime, isPublished, meetingLink, lastUpdatedAt.getTime)

  def getDate: String = getUKStringDate(startTime, dateFormatter)

  def getStartTime: String = getUKStringDate(startTime, timeFormatter)

  def getEndTime: String = getUKStringDate(endTime, timeFormatter)

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

  val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  def getUKStringDate(timestamp: Timestamp, formatter: DateTimeFormatter): String = zonedUKDateTime(timestamp).format(formatter)

  val zonedUKDateTime: Timestamp => ZonedDateTime = timestamp => timestamp.toInstant.atZone(ZoneId.of("Europe/London"))

}

class DropIns(tag: Tag) extends Table[DropInRow](tag, "drop_in") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def title: Rep[String] = column[String]("title")

  def startTime: Rep[Timestamp] = column[Timestamp]("start_time")

  def endTime: Rep[Timestamp] = column[Timestamp]("end_time")

  def isPublished: Rep[Boolean] = column[Boolean]("is_published")

  def meetingLink: Rep[Option[String]] = column[Option[String]]("meeting_link")

  def lastUpdatedAt: Rep[Timestamp] = column[Timestamp]("last_updated_at")

  def * : ProvenShape[DropInRow] = (id, title, startTime, endTime, isPublished, meetingLink, lastUpdatedAt).mapTo[DropInRow]
}

trait DropInTableLike {
  def updatePublishDropIn(dropInId: String, publish: Boolean): Future[Int]

  def getFuturePublishedDropIns()(implicit ec: ExecutionContext): Future[Seq[DropIn]]

  def getDropIns(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[DropInRow]]
}

case class DropInTable(tables: Tables) extends DropInTableLike {
  val dropInTable = TableQuery[DropIns]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)

  def updatePublishDropIn(dropInId: String, publish: Boolean): Future[Int] = {
    val query = dropInTable.filter(_.id === dropInId.trim.toInt).map(f => (f.isPublished, f.lastUpdatedAt))
      .update(publish, getCurrentTime)
    tables.run(query)
  }

  def getFuturePublishedDropIns()(implicit ec: ExecutionContext): Future[Seq[DropIn]] = {
    val query = dropInTable
      .filter(r => r.isPublished && r.startTime > new Timestamp(DateTime.now().withTimeAtStartOfDay().minusDays(1).getMillis))
      .sortBy(_.startTime)
      .result
    val result = tables.run(query)
    result.map(rows => rows.map(_.toDropIn))
  }

  override def getDropIns(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[DropInRow]] = {
    val intIds = ids.map(_.toInt).toSet
    val query = dropInTable.filter(_.id.inSet(intIds)).result
    tables.run(query)
  }
}
