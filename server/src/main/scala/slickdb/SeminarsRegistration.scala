package slickdb

import drt.shared.{SeminarRegistration}
import org.joda.time.DateTime
import slick.lifted.ProvenShape
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

case class SeminarsRegistrationRow(email: String,
                                   seminar_id: Int,
                                   register_time: Timestamp,
                                   email_sent: Option[Timestamp]) {
  def toSeminarRegistration: SeminarRegistration = SeminarRegistration(email, seminar_id, register_time.getTime, email_sent.map(_.getTime))
}

class SeminarsRegistration(tag: Tag) extends Table[SeminarsRegistrationRow](tag, "seminar_registration") {

  def email: Rep[String] = column[String]("email")

  def seminarId: Rep[Int] = column[Int]("seminar_id")

  def registerTime: Rep[Timestamp] = column[Timestamp]("register_time")

  def emailSent: Rep[Option[Timestamp]] = column[Option[Timestamp]]("email_sent")

  def * : ProvenShape[SeminarsRegistrationRow] = (email, seminarId, registerTime, emailSent).mapTo[SeminarsRegistrationRow]

  val pk = primaryKey("seminar_registration_pkey", (email, seminarId))

}

trait SeminarsRegistrationTableLike {
  def registerSeminars(email: String, ids: Seq[String])(implicit ex: ExecutionContext): Future[Seq[Int]]
}

case class SeminarsRegistrationTable(tables: Tables) extends SeminarsRegistrationTableLike {
  val seminarsRegistrationTable = TableQuery[SeminarsRegistration]

  private def getCurrentTime = new Timestamp(new DateTime().getMillis)


  def registerSeminars(email: String, ids: Seq[String])(implicit ex: ExecutionContext): Future[Seq[Int]] = {
    Future.sequence(ids.map { id =>
      val insertAction = seminarsRegistrationTable += SeminarsRegistrationRow(email, id.toInt, getCurrentTime, Some(getCurrentTime))
      tables.run(insertAction)
    })
  }


}
