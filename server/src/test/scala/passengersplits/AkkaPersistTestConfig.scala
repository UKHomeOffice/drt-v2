package passengersplits

import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._


object AkkaPersistTestConfig {

  val inMemoryAkkaPersistConfig = ConfigFactory.parseMap(Map(
    "akka.persistence.journal.plugin" -> "inmemory-journal",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.snapshot-store.plugin" -> "inmemory-snapshot-store"
  ))
}





