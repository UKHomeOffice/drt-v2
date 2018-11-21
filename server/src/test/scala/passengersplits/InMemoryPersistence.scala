package passengersplits

import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._


object InMemoryPersistence {

  val akkaAndAggregateDbConfig = ConfigFactory.parseMap(Map(
    "akka.persistence.journal.plugin" -> "inmemory-journal",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.snapshot-store.plugin" -> "inmemory-snapshot-store",

    "aggregated-db.connectionPool" -> "disabled",
    "aggregated-db.driver" -> "org.h2.Driver",
    "aggregated-db.url" -> "jdbc:h2:mem:drt;DB_CLOSE_DELAY=-1",
    "aggregated-db.keepAliveConnection" -> "true"
  ))
}





