package actors.persistent

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.stream.Materializer
import akka.stream.Supervision.Stop
import akka.stream.testkit.TestSubscriber.Probe
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.{AfterAll, AfterEach}
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.ports.AirportConfig

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class SnapshotTest extends TestKit(ActorSystem("DRT-TEST", PersistenceTestKitSnapshotPlugin.config.withFallback(ConfigFactory.load())))
  with SpecificationLike
  with AfterAll
  with AfterEach {

  isolated
  sequential

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  var maybeDrtActor: Option[ActorRef] = None

  override def afterAll: Unit = {
    maybeDrtActor.foreach(shutDownDrtActor)
  }

  override def after: Unit = {
    log.info("Shutting down actor system!!!")
    TestKit.shutdownActorSystem(system)
  }

  implicit val materializer: Materializer = Materializer.createMaterializer(system)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5.seconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000

  val defaultAirportConfig: AirportConfig = TestDefaults.airportConfig

  def shutDownDrtActor(drtActor: ActorRef): Terminated = {
    log.info("Shutting down drt actor")
    watch(drtActor)
    drtActor ! Stop
    expectMsgClass(classOf[Terminated])
  }

}