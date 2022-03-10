package manifests.passengers

import org.specs2.mutable.Specification


class LiveManifestPollerTest extends Specification {
  "A manifest poller" should {
    "work" in {
      val lastFileName = "some.zip"

      1 === 1
    }
  }
}

//  case class LiveManifestPoller(db: Db, portCode: PortCode)
//                               (implicit ec: ExecutionContext) {
//    private val log = LoggerFactory.getLogger(getClass)
//
//    import db.tables.profile.api._
//
//    def manifestsAreAvailable(since: Long): Future[Boolean] = {
//      val ts = new Timestamp(since)
//      val port = portCode.iata
//
//      val query: DBIOAction[Boolean, NoStream, Effect] =
//        sql"""SELECT
//             |  COUNT(*)
//             |FROM processed_zip pz
//             |INNER JOIN processed_json pj ON pz.zip_file_name = pj.zip_file_name
//             |INNER JOIN voyage_manifest_passenger_info vm ON vm.json_file = pj.json_file_name
//             |WHERE pz.processed_at >= $ts AND vm.arrival_port_code = $port
//             |GROUP BY vm.arrival_port_code, vm.departure_port_code, vm.carrier_code, vm.voyage_number, vm.scheduled_date;
//     """.stripMargin
//          .as[Int]
//          .map { r =>
//            log.info(s"manifestsAreAvailable: $r")
//            r.forall(_ > 0)
//          }
//
//      db.con.run(query)
//    }
//  }
//
//  object PostgresTables extends {
//    val profile = PostgresProfile
//  } with Tables
//
//  trait Db {
//    val tables: Tables
//    val con: tables.profile.backend.Database
//  }
//}
