package slickdb

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.auth.LoggedInUser

import java.sql.Timestamp
import java.util.Date
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class UserRow(
                    id: String,
                    username: String,
                    email: String,
                    latest_login: java.sql.Timestamp,
                    inactive_email_sent: Option[java.sql.Timestamp],
                    revoked_access: Option[java.sql.Timestamp])

trait UserTableLike {

  def selectAll: Future[Seq[UserRow]]

  def selectUser(email: String)(implicit ec: ExecutionContext): Future[Option[UserRow]]

  def removeUser(email: String)(implicit ec: ExecutionContext): Future[Int]

  def insertOrUpdateUser(user: LoggedInUser,
                         inactive_email_sent: Option[java.sql.Timestamp],
                         revoked_access: Option[java.sql.Timestamp])(implicit ec: ExecutionContext): Future[Int]
}

case class UserTable(tables: Tables) extends UserTableLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._
  import tables.User

  val userTableQuery = TableQuery[User]

  def selectAll: Future[Seq[UserRow]] = {
    tables.run(userTableQuery.result).mapTo[Seq[UserRow]]
  }

  def selectUser(email: String)(implicit ec: ExecutionContext): Future[Option[UserRow]] = {
    tables.run(userTableQuery.filter(_.email === email).result).mapTo[Seq[UserRow]].map(_.headOption)
  }

  def removeUser(id: String)(implicit ec: ExecutionContext): Future[Int] = {
    tables.run(userTableQuery.filter(matchId(id)).delete)
          .recover {
            case throwable =>
              log.error(s"delete failed", throwable)
              0
          }
  }

  def insertOrUpdateUser(user: LoggedInUser,
                         inactive_email_sent: Option[java.sql.Timestamp],
                         revoked_access: Option[java.sql.Timestamp])(implicit ec: ExecutionContext): Future[Int] = {
    tables.run(userTableQuery.insertOrUpdate(
      UserRow(user.id, user.userName, user.email, new Timestamp(new Date().getTime), inactive_email_sent, revoked_access)))
          .recover {
            case throwable =>
              log.error(s"insertOrUpdate failed", throwable)
              0
          }
  }

  def matchId(id: String): tables.User => Rep[Boolean] = (userTracking: User) =>
    userTracking.id == id
}
