package controllers

import actors.{DrtSystemInterface, ProdDrtSystem}
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import test.TestDrtSystem

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object DrtActorSystem extends AirportConfProvider {
  val config: Configuration = new Configuration(ConfigFactory.load)
  val isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("live") == "test"
  implicit val actorSystem: ActorSystem = if (isTestEnvironment) ActorSystem("DRT", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load())) else ActorSystem("DRT")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  lazy val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(actorSystem)

  val drtSystem: DrtSystemInterface =
    if (isTestEnvironment) drtTestSystem
    else drtProdSystem

  lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(getPortConfFromEnvVar, persistenceTestKit, actorSystem)
  lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(getPortConfFromEnvVar)

}