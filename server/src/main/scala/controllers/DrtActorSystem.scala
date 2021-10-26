package controllers

import actors.{DrtSystemInterface, ProdDrtSystem}
import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import test.TestDrtSystem

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object DrtActorSystem extends AirportConfProvider {
  val config: Configuration = new Configuration(ConfigFactory.load)
  val isTestEnvironment: Boolean = config.getOptional[String]("env").getOrElse("live") == "test"
  implicit val actorSystem: ActorSystem = if (isTestEnvironment) ActorSystem("DRT", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.load())) else ActorSystem("DRT")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5.seconds)
  val drtSystem: DrtSystemInterface =
    if (isTestEnvironment) drtTestSystem
    else drtProdSystem

  lazy val drtTestSystem: TestDrtSystem = TestDrtSystem(getPortConfFromEnvVar)
  lazy val drtProdSystem: ProdDrtSystem = ProdDrtSystem(getPortConfFromEnvVar)

}
