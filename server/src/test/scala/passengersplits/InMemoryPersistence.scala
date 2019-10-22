package passengersplits

import com.typesafe.config.{Config, ConfigFactory}

import collection.JavaConverters._


object InMemoryPersistence {
  val akkaAndAggregateDbConfig: Config = ConfigFactory.parseMap(Map(
    "akka.loglevel" -> "WARNING",
    "akka.actor.warn-about-java-serializer-usage" -> false,

    "akka.persistence.journal.plugin" -> "inmemory-journal",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.snapshot-store.plugin" -> "inmemory-snapshot-store",

    "aggregated-db.connectionPool" -> "disabled",
    "aggregated-db.driver" -> "org.h2.Driver",
    "aggregated-db.url" -> "jdbc:h2:mem:drt;DB_CLOSE_DELAY=-1",
    "aggregated-db.keepAliveConnection" -> "true"
  ).asJava)
}
