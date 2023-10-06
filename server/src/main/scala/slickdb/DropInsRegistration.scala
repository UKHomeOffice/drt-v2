package slickdb

import drt.shared.DropInRegistration
import org.joda.time.DateTime
import slick.lifted.ProvenShape
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class DropInsRegistrationRow(email: String,
                                  dropInId: Int,
                                  registeredAt: Timestamp,
                                  emailSentAt: Option[Timestamp]) {
  def toDropInRegistration: DropInRegistration = DropInRegistration(email, dropInId, registeredAt.getTime, emailSentAt.map(_.getTime))
}

class DropInsRegistration(tag: Tag) extends Table[DropInsRegistrationRow](tag, "drop_in_registration") {

  def email: Rep[String] = column[String]("email")

  def dropInId: Rep[Int] = column[Int]("drop_in_id")

  def registeredAt: Rep[Timestamp] = column[Timestamp]("registered_at")

  def emailSentAt: Rep[Option[Timestamp]] = column[Option[Timestamp]]("email_sent_at")

  def * : ProvenShape[DropInsRegistrationRow] = (email, dropInId, registeredAt, emailSentAt).mapTo[DropInsRegistrationRow]

  val pk = primaryKey("drop_in_registration_pkey", (email, dropInId))

}

trait DropInsRegistrationTableLike {
  def createDropInRegistration(email: String, id: String)(implicit ex: ExecutionContext): Future[Int]

  def getDropInRegistrations(email: String)(implicit ex: ExecutionContext): Future[Seq[DropInsRegistrationRow]]
}

case class DropInsRegistrationTable(tables: Tables) extends DropInsRegistrationTableLike {
  val dropInsRegistrationTable = TableQuery[DropInsRegistration]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)


  def createDropInRegistration(email: String, id: String)(implicit ex: ExecutionContext): Future[Int] = {
      val insertAction = dropInsRegistrationTable += DropInsRegistrationRow(email, id.toInt, getCurrentTime, Some(getCurrentTime))
      tables.run(insertAction)
  }

  def getDropInRegistrations(email: String)(implicit ex: ExecutionContext): Future[Seq[DropInsRegistrationRow]] = {
    val query = dropInsRegistrationTable.filter(_.email === email).sortBy(_.registeredAt.desc).result
    val result = tables.run(query)
    result
  }

}
