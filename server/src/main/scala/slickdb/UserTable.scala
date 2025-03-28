package slickdb

import drt.shared.UserPreferences
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

case class UserRow(
                    id: String,
                    username: String,
                    email: String,
                    latest_login: java.sql.Timestamp,
                    inactive_email_sent: Option[java.sql.Timestamp],
                    revoked_access: Option[java.sql.Timestamp],
                    drop_in_notification_at: Option[java.sql.Timestamp],
                    created_at: Option[java.sql.Timestamp],
                    feedback_banner_closed_at: Option[java.sql.Timestamp],
                    staff_planning_interval_minutes: Option[Int],
                    hide_pax_data_source_description: Option[Boolean],
                    show_staffing_shift_view: Option[Boolean]
                  )

trait UserTableLike {

  def selectUser(email: String)(implicit ec: ExecutionContext): Future[Option[UserRow]]

  def removeUser(email: String)(implicit ec: ExecutionContext): Future[Int]

  def upsertUser(userData: UserRow)(implicit ec: ExecutionContext): Future[Int]

  def updateCloseBanner(email: String, at: java.sql.Timestamp)(implicit ec: ExecutionContext): Future[Int]

  def updateStaffPlanningIntervalMinutes(email: String, periodInterval: Int)(implicit ec: ExecutionContext): Future[Int]

  def updateUserPreferences(email: String, userPreferences: UserPreferences)(implicit ec: ExecutionContext): Future[Int]
}

case class UserTable(tables: AggregatedDbTables) extends UserTableLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  val userTableQuery = TableQuery[tables.UserTable]

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

  def updateCloseBanner(email: String, at: java.sql.Timestamp)(implicit ec: ExecutionContext): Future[Int] = {
    val query = userTableQuery.filter(_.email === email)
      .map(f => (f.feedback_banner_closed_at))
      .update(Option(at))
    tables.run(query).recover {
      case throwable =>
        log.error(s"updateCloseBanner failed", throwable)
        0
    }
  }

  def upsertUser(user: UserRow)(implicit ec: ExecutionContext): Future[Int] = {
    for {
      updateCount <- updateUser(user)
      result <- if (updateCount > 0) Future.successful(updateCount) else insertOrUpdateUser(user)
    } yield result
  }.recover {
    case throwable =>
      log.error("Upsert failed", throwable)
      0
  }

  private def insertOrUpdateUser(user: UserRow)(implicit ec: ExecutionContext): Future[Int] = {
    tables.run(userTableQuery.insertOrUpdate(user))
      .recover {
        case throwable =>
          log.error(s"insertOrUpdate failed", throwable)
          0
      }
  }

  private def updateUser(user: UserRow): Future[Int] = {
    val query = userTableQuery.filter(_.email === user.email)
      .map(f => (f.latest_login, f.inactive_email_sent, f.revoked_access))
      .update((user.latest_login, user.inactive_email_sent, user.revoked_access))
    tables.run(query)
  }

  def matchId(id: String): tables.UserTable => Rep[Boolean] = (userTracking: tables.UserTable) =>
    userTracking.id === id

  override def updateStaffPlanningIntervalMinutes(email: String, periodInterval: Int)(implicit ec: ExecutionContext): Future[Int] = {
    val query = userTableQuery.filter(_.email === email)
      .map(f => (f.staff_planning_interval_minutes))
      .update(Option(periodInterval))
    tables.run(query).recover {
      case throwable =>
        log.error(s"updateStaffPlanningTimePeriod failed", throwable)
        0
    }
  }

  override def updateUserPreferences(email: String, userPreferences: UserPreferences)(implicit ec: ExecutionContext): Future[Int] = {
    val query = userTableQuery.filter(_.email === email)
      .map(f => (f.staff_planning_interval_minutes, f.hide_pax_data_source_description, f.show_staffing_shift_view))
      .update(Option(userPreferences.userSelectedPlanningTimePeriod), Option(userPreferences.hidePaxDataSourceDescription), Option(userPreferences.showStaffingShiftView))
    tables.run(query).recover {
      case throwable =>
        log.error(s"updateUserPreferences failed", throwable)
        0
    }
  }

}