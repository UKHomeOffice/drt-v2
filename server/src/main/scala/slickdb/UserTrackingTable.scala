package slickdb

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.auth.LoggedInUser

import java.sql.Timestamp
import java.util.Date
import scala.concurrent.{ExecutionContext, Future}


case class UserTrackingRow(
                            id: String,
                            userName: String,
                            email: String,
                            latest_login: java.sql.Timestamp,
                            inactive_email_sent: Option[java.sql.Timestamp],
                            revoke_access: Option[java.sql.Timestamp])

trait UserTrackingTableLike {

  def selectAll: Future[Seq[UserTrackingRow]]

  def removeUserTracking(email: String)(implicit ec: ExecutionContext): Future[Int]

  def insertOrUpdateArrival(user: LoggedInUser, inactive_email_sent: Option[java.sql.Timestamp], revoke_access: Option[java.sql.Timestamp])(implicit ec: ExecutionContext): Future[Int]
}


case class UserTrackingTable(tables: Tables) extends UserTrackingTableLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._
  import tables.UserTracking

  val userTrackingTableQuery = TableQuery[UserTracking]

  def selectAll: Future[Seq[UserTrackingRow]] = {
    tables.run(userTrackingTableQuery.result).mapTo[Seq[UserTrackingRow]]
  }

  def removeUserTracking(email: String)(implicit ec: ExecutionContext): Future[Int] = {
    tables.run(userTrackingTableQuery.filter(matchIndex(email)).delete)
      .recover {
        case throwable =>
          log.error(s"delete failed", throwable)
          0
      }
  }

  def insertOrUpdateArrival(user: LoggedInUser, inactive_email_sent: Option[java.sql.Timestamp], revoke_access: Option[java.sql.Timestamp])(implicit ec: ExecutionContext): Future[Int] = {
    tables.run(userTrackingTableQuery.insertOrUpdate(UserTrackingRow(user.userName, user.id, user.email, new Timestamp(new Date().getTime), inactive_email_sent, revoke_access)))
      .recover {
        case throwable =>
          log.error(s"insertOrUpdate failed", throwable)
          0
      }
  }

  def matchIndex(email: String): tables.UserTracking => Rep[Boolean] = (userTracking: UserTracking) =>
    userTracking.email == email
}