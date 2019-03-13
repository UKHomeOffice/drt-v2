package manifests

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.specs2.mutable.{Specification, SpecificationLike}
import passengersplits.InMemoryPersistence
import services.SDate
import slickdb.{Tables, VoyageManifestPassengerInfoTable}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConversions._


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
  ))
}

object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

class ManifestLookupSpec extends TestKit(ActorSystem("ManifestLookup", PostgresPersistence.akkaAndAggregateDbConfig))
  with SpecificationLike {
  skipped("Exploratory")
  val table = VoyageManifestPassengerInfoTable(PostgresTables)

//  override def before: Any = {
//    clearDatabase()
//  }
//
//  def clearDatabase(): Unit = {
//    Try(dropTables())
//    createTables()
//  }
//
//  def createTables(): Unit = {
//    H2Tables.schema.createStatements.toList.foreach { query =>
//      println(s"running $query")
//      Await.ready(table.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 10 seconds)
//    }
//  }
//
//  def dropTables(): Unit = {
//    H2Tables.schema.dropStatements.toList.reverse.foreach { query =>
//      println(s"running $query")
//      Await.ready(table.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 10 seconds)
//    }
//  }

  "something" >> {
    val lookupService = ManifestLookup(table)

    Await.result(lookupService.maybeBestAvailableManifest("LHR", "ORD", "0938", SDate("2019-04-01T00:00:00Z")), 5 seconds)

    success
  }
}
