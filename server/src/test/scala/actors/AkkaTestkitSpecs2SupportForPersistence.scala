package actors

import java.io.File

import akka.actor.{ActorSystem, Terminated}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.After

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


object PersistenceCleanup {
  def deleteJournal(dbLocation: String): Unit = {
    val directory = new File(dbLocation)
    Option(directory.listFiles())
      .foreach(files => files.foreach((file: File) => {
        if (file.isDirectory) deleteJournal(file.getPath) else file.delete()
      }))
  }
}

abstract class AkkaTestkitSpecs2SupportForPersistence(val dbLocation: String) extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "akka.actor.warn-about-java-serializer-usage" -> false,
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
  "akka.persistence.journal.leveldb.dir" -> dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"$dbLocation/snapshot"
).asJava)))
  with After
  with ImplicitSender {

  def after: Unit = {
    shutDownActorSystem
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
  }

  def shutDownActorSystem: Future[Terminated] = {
    //TODO figure out how to wait for the actor to finish saving rather than this nasty timer.
    Thread.sleep(200)
        Await.ready(system.terminate(), 2 second)
    Await.ready(system.whenTerminated, 2 second)
  }
}
