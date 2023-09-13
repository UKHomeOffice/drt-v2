package slickdb

import drt.shared.{SeminarRegistration}
import org.joda.time.DateTime
import slick.lifted.ProvenShape
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class SeminarsRegistrationRow(email: String,
                                   seminarId: Int,
                                   registeredAt: Timestamp,
                                   emailSentAt: Option[Timestamp]) {
  def toSeminarRegistration: SeminarRegistration = SeminarRegistration(email, seminarId, registeredAt.getTime, emailSentAt.map(_.getTime))
}

class SeminarsRegistration(tag: Tag) extends Table[SeminarsRegistrationRow](tag, "seminar_registration") {

  def email: Rep[String] = column[String]("email")

  def seminarId: Rep[Int] = column[Int]("seminar_id")

  def registeredAt: Rep[Timestamp] = column[Timestamp]("registered_at")

  def emailSentAt: Rep[Option[Timestamp]] = column[Option[Timestamp]]("email_sent_at")

  def * : ProvenShape[SeminarsRegistrationRow] = (email, seminarId, registeredAt, emailSentAt).mapTo[SeminarsRegistrationRow]

  val pk = primaryKey("seminar_registration_pkey", (email, seminarId))

}

trait SeminarsRegistrationTableLike {
  def registerSeminars(email: String, id: String)(implicit ex: ExecutionContext): Future[Int]
}

case class SeminarsRegistrationTable(tables: Tables) extends SeminarsRegistrationTableLike {
  val seminarsRegistrationTable = TableQuery[SeminarsRegistration]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)


  def registerSeminars(email: String, id: String)(implicit ex: ExecutionContext): Future[Int] = {
      val insertAction = seminarsRegistrationTable += SeminarsRegistrationRow(email, id.toInt, getCurrentTime, Some(getCurrentTime))
      tables.run(insertAction)
  }


}
