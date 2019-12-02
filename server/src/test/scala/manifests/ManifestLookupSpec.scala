package manifests

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import drt.shared.{PortCode, VoyageNumber}
import org.specs2.mutable.SpecificationLike
import services.SDate
import slickdb.{Tables, VoyageManifestPassengerInfoTable}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._


object PostgresPersistence {

  val akkaAndAggregateDbConfig: Config = ConfigFactory.parseMap(Map(
    "akka.actor.warn-about-java-serializer-usage" -> false,

    "akka.persistence.journal.plugin" -> "inmemory-journal",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.snapshot-store.plugin" -> "inmemory-snapshot-store",

    "aggregated-db.connectionPool" -> "HikariCP",
    "aggregated-db.driver" -> "org.postgresql.Driver",
    "aggregated-db.url" -> "jdbc:postgresql://localhost:5432/aggregated?user=drt&password=drt&ssl=true",
    "aggregated-db.keepAliveConnection" -> "true"
  ).asJava)
}

object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

class ManifestLookupSpec extends TestKit(ActorSystem("ManifestLookup", PostgresPersistence.akkaAndAggregateDbConfig))
  with SpecificationLike {
  val table = VoyageManifestPassengerInfoTable(PostgresTables)

  "something" >> {
    skipped("Exploratory")
    val lookupService = ManifestLookup(table)

    Await.result(lookupService.maybeBestAvailableManifest(PortCode("LHR"), PortCode("ORD"), VoyageNumber(938), SDate("2019-04-01T00:00:00Z")), 5 seconds)

    success
  }
}
