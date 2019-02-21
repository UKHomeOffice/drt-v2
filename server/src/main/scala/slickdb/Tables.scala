package slickdb

import slick.jdbc.PostgresProfile

/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
trait Tables {
  val profile: slick.jdbc.JdbcProfile
  import profile.api._
  import slick.model.ForeignKeyAction
  // NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.
  import slick.jdbc.{GetResult => GR}

  /** DDL for all tables. Call .create to execute. */
  lazy val schema: profile.SchemaDescription = Arrival.schema
  @deprecated("Use .schema instead of .ddl", "3.0")
  def ddl = schema

  /** Entity class storing rows of table Arrival
    *  @param code Database column code SqlType(text)
    *  @param number Database column number SqlType(int4)
    *  @param destination Database column destination SqlType(text)
    *  @param origin Database column origin SqlType(text)
    *  @param terminal Database column terminal SqlType(text)
    *  @param gate Database column gate SqlType(text), Default(None)
    *  @param stand Database column stand SqlType(text), Default(None)
    *  @param status Database column status SqlType(text)
    *  @param scheduled Database column scheduled SqlType(timestamp)
    *  @param estimated Database column estimated SqlType(timestamp), Default(None)
    *  @param actual Database column actual SqlType(timestamp), Default(None)
    *  @param estimatedchox Database column estimatedchox SqlType(timestamp), Default(None)
    *  @param actualchox Database column actualchox SqlType(timestamp), Default(None)
    *  @param pcp Database column pcp SqlType(timestamp)
    *  @param totalpassengers Database column totalpassengers SqlType(int4), Default(None)
    *  @param pcppassengers Database column pcppassengers SqlType(int4), Default(None) */
  case class ArrivalRow(code: String, number: Int, destination: String, origin: String, terminal: String, gate: Option[String] = None, stand: Option[String] = None, status: String, scheduled: java.sql.Timestamp, estimated: Option[java.sql.Timestamp] = None, actual: Option[java.sql.Timestamp] = None, estimatedchox: Option[java.sql.Timestamp] = None, actualchox: Option[java.sql.Timestamp] = None, pcp: java.sql.Timestamp, totalpassengers: Option[Int] = None, pcppassengers: Option[Int] = None)
  /** GetResult implicit for fetching ArrivalRow objects using plain SQL queries */
  implicit def GetResultArrivalRow(implicit e0: GR[String], e1: GR[Int], e2: GR[Option[String]], e3: GR[java.sql.Timestamp], e4: GR[Option[java.sql.Timestamp]], e5: GR[Option[Int]]): GR[ArrivalRow] = GR{
    prs => import prs._
      ArrivalRow.tupled((<<[String], <<[Int], <<[String], <<[String], <<[String], <<?[String], <<?[String], <<[String], <<[java.sql.Timestamp], <<?[java.sql.Timestamp], <<?[java.sql.Timestamp], <<?[java.sql.Timestamp], <<?[java.sql.Timestamp], <<[java.sql.Timestamp], <<?[Int], <<?[Int]))
  }
  /** Table description of table arrival. Objects of this class serve as prototypes for rows in queries. */
  class Arrival(_tableTag: Tag) extends {
    private val maybeSchema = profile match {
      case _: PostgresProfile => Some("public")
      case _ => None
    }
  } with profile.api.Table[ArrivalRow](_tableTag, maybeSchema, "arrival") {
    def * = (code, number, destination, origin, terminal, gate, stand, status, scheduled, estimated, actual, estimatedchox, actualchox, pcp, totalpassengers, pcppassengers) <> (ArrivalRow.tupled, ArrivalRow.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (Rep.Some(code), Rep.Some(number), Rep.Some(destination), Rep.Some(origin), Rep.Some(terminal), gate, stand, Rep.Some(status), Rep.Some(scheduled), estimated, actual, estimatedchox, actualchox, Rep.Some(pcp), totalpassengers, pcppassengers).shaped.<>({r=>import r._; _1.map(_=> ArrivalRow.tupled((_1.get, _2.get, _3.get, _4.get, _5.get, _6, _7, _8.get, _9.get, _10, _11, _12, _13, _14.get, _15, _16)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column code SqlType(text) */
    val code: Rep[String] = column[String]("code")
    /** Database column number SqlType(int4) */
    val number: Rep[Int] = column[Int]("number")
    /** Database column destination SqlType(text) */
    val destination: Rep[String] = column[String]("destination")
    /** Database column origin SqlType(text) */
    val origin: Rep[String] = column[String]("origin")
    /** Database column terminal SqlType(text) */
    val terminal: Rep[String] = column[String]("terminal")
    /** Database column gate SqlType(text), Default(None) */
    val gate: Rep[Option[String]] = column[Option[String]]("gate", O.Default(None))
    /** Database column stand SqlType(text), Default(None) */
    val stand: Rep[Option[String]] = column[Option[String]]("stand", O.Default(None))
    /** Database column status SqlType(text) */
    val status: Rep[String] = column[String]("status")
    /** Database column scheduled SqlType(timestamp) */
    val scheduled: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("scheduled")
    /** Database column estimated SqlType(timestamp), Default(None) */
    val estimated: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("estimated", O.Default(None))
    /** Database column actual SqlType(timestamp), Default(None) */
    val actual: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("actual", O.Default(None))
    /** Database column estimatedchox SqlType(timestamp), Default(None) */
    val estimatedchox: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("estimatedchox", O.Default(None))
    /** Database column actualchox SqlType(timestamp), Default(None) */
    val actualchox: Rep[Option[java.sql.Timestamp]] = column[Option[java.sql.Timestamp]]("actualchox", O.Default(None))
    /** Database column pcp SqlType(timestamp) */
    val pcp: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("pcp")
    /** Database column totalpassengers SqlType(int4), Default(None) */
    val totalpassengers: Rep[Option[Int]] = column[Option[Int]]("totalpassengers", O.Default(None))
    /** Database column pcppassengers SqlType(int4), Default(None) */
    val pcppassengers: Rep[Option[Int]] = column[Option[Int]]("pcppassengers", O.Default(None))

    /** Primary key of Arrival (database name arrival_pkey) */
    val pk = primaryKey("arrival_pkey", (number, destination, terminal, scheduled))

    /** Index over (code) (database name code) */
    val index1 = index("code", code)
    /** Index over (number) (database name number) */
    val index2 = index("number", number)
    /** Index over (origin) (database name origin) */
    val index3 = index("origin", origin)
    /** Index over (pcp) (database name pcp) */
    val index4 = index("pcp", pcp)
    /** Index over (scheduled) (database name scheduled) */
    val index5 = index("scheduled", scheduled)
    /** Index over (terminal) (database name terminal) */
    val index6 = index("terminal", terminal)
  }
  /** Collection-like TableQuery object for table Arrival */
  lazy val Arrival = new TableQuery(tag => new Arrival(tag))

  case class VoyageManifestPassengerInfoRow(event_code: String,
                                            arrival_port_code: String,
                                            departure_port_code: String,
                                            voyager_number: String,
                                            carrier_code: String,
                                            scheduled_date: java.sql.Timestamp,
                                            document_type: String,
                                            document_issuing_country_code: String,
                                            eea_flag: String,
                                            age: String,
                                            disembarkation_port_code: String,
                                            in_transit_flag: String,
                                            disembarkation_port_country_code: String,
                                            nationality_country_code: String,
                                            passenger_identifier: String,
                                            in_transit: Boolean)
  /** GetResult implicit for fetching ArrivalRow objects using plain SQL queries */
  implicit def GetResultVoyageManifestPassengerInfoRow(implicit e0: GR[String], e1: GR[java.sql.Timestamp]): GR[VoyageManifestPassengerInfoRow] = GR{
    prs => import prs._
      VoyageManifestPassengerInfoRow.tupled((<<[String], <<[String], <<[String], <<[String], <<[String], <<[java.sql.Timestamp], <<[String], <<[String], <<[String], <<[String], <<[String], <<[String], <<[String], <<[String], <<[String], <<[Boolean]))
  }
  /** Table description of table arrival. Objects of this class serve as prototypes for rows in queries. */
  class VoyageManifestPassengerInfo(_tableTag: Tag) extends {
    private val maybeSchema = profile match {
      case _: PostgresProfile => Some("public")
      case _ => None
    }
  } with profile.api.Table[VoyageManifestPassengerInfoRow](_tableTag, maybeSchema, "voyage_manifest_passenger_info") {
    def * = (event_code, arrival_port_code, departure_port_code, voyager_number, carrier_code, scheduled_date, document_type, document_issuing_country_code, eea_flag, age, disembarkation_port_code, in_transit_flag, disembarkation_port_country_code, nationality_country_code, passenger_identifier, in_transit) <> (VoyageManifestPassengerInfoRow.tupled, VoyageManifestPassengerInfoRow.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (event_code, arrival_port_code, departure_port_code, voyager_number, carrier_code, scheduled_date, document_type, document_issuing_country_code, eea_flag, age, disembarkation_port_code, in_transit_flag, disembarkation_port_country_code, nationality_country_code, passenger_identifier, in_transit).shaped.<>({r=>import r._; _1.map(_=> VoyageManifestPassengerInfoRow.tupled((_1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column code SqlType(text) */
    val event_code: Rep[String] = column[String]("event_code")
    /** Database column number SqlType(int4) */
    val arrival_port_code: Rep[String] = column[String]("arrival_port_code")
    /** Database column destination SqlType(text) */
    val departure_port_code: Rep[String] = column[String]("departure_port_code")
    /** Database column origin SqlType(text) */
    val voyager_number: Rep[String] = column[String]("voyager_number")
    /** Database column terminal SqlType(text) */
    val carrier_code: Rep[String] = column[String]("carrier_code")
    /** Database column gate SqlType(text), Default(None) */
    val scheduled_date: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("scheduled_date")
    /** Database column stand SqlType(text), Default(None) */
    val document_type: Rep[String] = column[String]("document_type")
    /** Database column status SqlType(text) */
    val document_issuing_country_code: Rep[String] = column[String]("document_issuing_country_code")
    /** Database column scheduled SqlType(timestamp) */
    val eea_flag: Rep[String] = column[String]("eea_flag")
    /** Database column estimated SqlType(timestamp), Default(None) */
    val age: Rep[String] = column[String]("age")
    /** Database column actual SqlType(timestamp), Default(None) */
    val disembarkation_port_code: Rep[String] = column[String]("disembarkation_port_code")
    /** Database column estimatedchox SqlType(timestamp), Default(None) */
    val in_transit_flag: Rep[String] = column[String]("in_transit_flag")
    /** Database column actualchox SqlType(timestamp), Default(None) */
    val disembarkation_port_country_code: Rep[String] = column[String]("disembarkation_port_country_code")
    /** Database column pcp SqlType(timestamp) */
    val nationality_country_code: Rep[String] = column[String]("nationality_country_code")
    /** Database column totalpassengers SqlType(int4), Default(None) */
    val passenger_identifier: Rep[String] = column[String]("passenger_identifier")
    /** Database column pcppassengers SqlType(int4), Default(None) */
    val in_transit: Rep[Boolean] = column[Boolean]("in_transit")
  }
  /** Collection-like TableQuery object for table VoyageManifestPassengerInfo */
  lazy val VoyageManifestPassengerInfo = new TableQuery(tag => new VoyageManifestPassengerInfo(tag))
}
