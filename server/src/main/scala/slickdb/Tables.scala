package slickdb

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.PostgresProfile

import java.sql.Timestamp
import scala.concurrent.Future

/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
trait Tables {
  val profile: slick.jdbc.JdbcProfile

  def run[R](a: DBIOAction[R, NoStream, Nothing]): Future[R]

  import profile.api._
  // NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.

  /** DDL for all tables. Call .create to execute. */
  lazy val schema: profile.SchemaDescription = VoyageManifestPassengerInfo.schema ++ ProcessedJson.schema ++ ProcessedZip.schema ++ Arrival.schema

  case class ProcessedZipRow(zip_file_name: String, success: Boolean, processed_at: Timestamp)

  case class ProcessedJsonRow(zip_file_name: String, json_file_name: String, suspicious_date: Boolean, success: Boolean, processed_at: Timestamp)

  case class VoyageManifestPassengerInfoRow(event_code: String,
                                            arrival_port_code: String,
                                            departure_port_code: String,
                                            voyage_number: Int,
                                            carrier_code: String,
                                            scheduled_date: java.sql.Timestamp,
                                            day_of_week: Int,
                                            week_of_year: Int,
                                            document_type: String,
                                            document_issuing_country_code: String,
                                            eea_flag: String,
                                            age: Int,
                                            disembarkation_port_code: String,
                                            in_transit_flag: String,
                                            disembarkation_port_country_code: String,
                                            nationality_country_code: String,
                                            passenger_identifier: String,
                                            in_transit: Boolean,
                                            json_file: String)

  case class ArrivalRow(code: String,
                        number: Int,
                        destination: String,
                        origin: String,
                        terminal: String,
                        gate: Option[String] = None,
                        stand: Option[String] = None,
                        status: String,
                        scheduled: java.sql.Timestamp,
                        estimated: Option[java.sql.Timestamp] = None,
                        actual: Option[java.sql.Timestamp] = None,
                        estimatedchox: Option[java.sql.Timestamp] = None,
                        actualchox: Option[java.sql.Timestamp] = None,
                        pcp: java.sql.Timestamp,
                        totalpassengers: Option[Int] = None,
                        pcppassengers: Option[Int] = None,
                        scheduled_departure : Option[java.sql.Timestamp] = None)

  private val maybeSchema = profile match {
    case _: PostgresProfile =>
      Some("public")
    case _ =>
      None
  }

  class ProcessedZip(_tableTag: Tag) extends profile.api.Table[ProcessedZipRow](_tableTag, maybeSchema, "processed_zip") {
    def * = (zip_file_name, success, processed_at) <> (ProcessedZipRow.tupled, ProcessedZipRow.unapply)

    val zip_file_name: Rep[String] = column[String]("zip_file_name")
    val success: Rep[Boolean] = column[Boolean]("success")
    val processed_at: Rep[Timestamp] = column[Timestamp]("processed_at")
  }

  class ProcessedJson(_tableTag: Tag) extends profile.api.Table[ProcessedJsonRow](_tableTag, maybeSchema, "processed_json") {
    def * = (zip_file_name, json_file_name, suspicious_date, success, processed_at) <> (ProcessedJsonRow.tupled, ProcessedJsonRow.unapply)

    val zip_file_name: Rep[String] = column[String]("zip_file_name")
    val json_file_name: Rep[String] = column[String]("json_file_name")
    val suspicious_date: Rep[Boolean] = column[Boolean]("suspicious_date")
    val success: Rep[Boolean] = column[Boolean]("success")
    val processed_at: Rep[Timestamp] = column[Timestamp]("processed_at")
  }

  /** Table description of table arrival. Objects of this class serve as prototypes for rows in queries. */
  class VoyageManifestPassengerInfo(_tableTag: Tag) extends profile.api.Table[VoyageManifestPassengerInfoRow](_tableTag, maybeSchema, "voyage_manifest_passenger_info") {
    def * = (event_code, arrival_port_code, departure_port_code, voyage_number, carrier_code, scheduled_date, day_of_week, week_of_year, document_type, document_issuing_country_code, eea_flag, age, disembarkation_port_code, in_transit_flag, disembarkation_port_country_code, nationality_country_code, passenger_identifier, in_transit, json_file) <> (VoyageManifestPassengerInfoRow.tupled, VoyageManifestPassengerInfoRow.unapply)

    val event_code: Rep[String] = column[String]("event_code")
    val arrival_port_code: Rep[String] = column[String]("arrival_port_code")
    val departure_port_code: Rep[String] = column[String]("departure_port_code")
    val voyage_number: Rep[Int] = column[Int]("voyage_number")
    val carrier_code: Rep[String] = column[String]("carrier_code")
    val scheduled_date: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("scheduled_date")
    val day_of_week: Rep[Int] = column[Int]("day_of_week")
    val week_of_year: Rep[Int] = column[Int]("week_of_year")
    val document_type: Rep[String] = column[String]("document_type")
    val document_issuing_country_code: Rep[String] = column[String]("document_issuing_country_code")
    val eea_flag: Rep[String] = column[String]("eea_flag")
    val age: Rep[Int] = column[Int]("age")
    val disembarkation_port_code: Rep[String] = column[String]("disembarkation_port_code")
    val in_transit_flag: Rep[String] = column[String]("in_transit_flag")
    val disembarkation_port_country_code: Rep[String] = column[String]("disembarkation_port_country_code")
    val nationality_country_code: Rep[String] = column[String]("nationality_country_code")
    val passenger_identifier: Rep[String] = column[String]("passenger_identifier")
    val in_transit: Rep[Boolean] = column[Boolean]("in_transit")
    val json_file: Rep[String] = column[String]("json_file")
  }

  /** Table description of table arrival. Objects of this class serve as prototypes for rows in queries. */
  class Arrival(_tableTag: Tag) extends {
    private val maybeSchema = profile match {
      case _: PostgresProfile => Some("public")
      case _ => None
    }
  } with profile.api.Table[ArrivalRow](_tableTag, maybeSchema, "arrival") {
    def * = (code, number, destination, origin, terminal, gate, stand, status, scheduled, estimated, actual, estimatedchox, actualchox, pcp, totalpassengers, pcppassengers,scheduled_departure) <> (ArrivalRow.tupled, ArrivalRow.unapply)

    val code: Rep[String] = column[String]("code")
    val number: Rep[Int] = column[Int]("number")
    val destination: Rep[String] = column[String]("destination")
    val origin: Rep[String] = column[String]("origin")
    val terminal: Rep[String] = column[String]("terminal")
    val gate: Rep[Option[String]] = column[Option[String]]("gate", O.Default(None))
    val stand: Rep[Option[String]] = column[Option[String]]("stand", O.Default(None))
    val status: Rep[String] = column[String]("status")
    val scheduled: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("scheduled")
    val estimated: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("estimated", O.Default(None))
    val actual: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("actual", O.Default(None))
    val estimatedchox: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("estimatedchox", O.Default(None))
    val actualchox: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("actualchox", O.Default(None))
    val pcp: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("pcp")
    val totalpassengers: Rep[Option[Int]] = column[Option[Int]]("totalpassengers", O.Default(None))
    val pcppassengers: Rep[Option[Int]] = column[Option[Int]]("pcppassengers", O.Default(None))
    val scheduled_departure: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("scheduled_departure", O.Default(None))

    val pk = primaryKey("arrival_pkey", (number, destination, terminal, scheduled))

    val index1 = index("code", code)
    val index2 = index("number", number)
    val index3 = index("origin", origin)
    val index4 = index("pcp", pcp)
    val index5 = index("scheduled", scheduled)
    val index6 = index("terminal", terminal)
    val index7 = index("scheduled_departure", scheduled_departure)
  }

  class User(_tableTag: Tag) extends profile.api.Table[UserRow](_tableTag, maybeSchema, "user") {
    def * = (id, userName, email, latest_login, inactive_email_sent, revoked_access) <> (UserRow.tupled, UserRow.unapply)

    val id: Rep[String] = column[String]("id")
    val userName: Rep[String] = column[String]("username")
    val email: Rep[String] = column[String]("email")
    val latest_login: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("latest_login")
    val inactive_email_sent: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("inactive_email_sent")
    val revoked_access: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("revoked_access")
    val pk = primaryKey("user_pkey", (id))
    val index1 = index("username", userName)
    val index2 = index("email", email)
    val index3 = index("latest_login", latest_login)
  }

  /** Collection-like TableQuery object for table VoyageManifestPassengerInfo */
  lazy val VoyageManifestPassengerInfo = new TableQuery(tag => new VoyageManifestPassengerInfo(tag))
  lazy val ProcessedJson = new TableQuery(tag => new ProcessedJson(tag))
  lazy val ProcessedZip = new TableQuery(tag => new ProcessedZip(tag))
  lazy val Arrival = new TableQuery(tag => new Arrival(tag))
  lazy val User = new TableQuery(tag => new User(tag))
}
