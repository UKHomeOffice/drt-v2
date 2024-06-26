package services.crunch

import actors.AggregatedArrivalsActor
import akka.Done
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import org.specs2.specification.BeforeEach
import services.crunch.TestAggregatedArrivalsActor.GetArrivals
import slick.jdbc.SQLActionBuilder
import slick.jdbc.SetParameter.SetUnit
import slickdb.{AggregatedArrival, AggregatedArrivals, ArrivalTable, ArrivalTableLike}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try


object UpdateHandled

object RemovalHandled

object TestAggregatedArrivalsActor {
  case object GetArrivals
}

class TestAggregatedArrivalsActor(arrivalTable: ArrivalTableLike, probe: ActorRef) extends AggregatedArrivalsActor(arrivalTable) {
  def testReceive: Receive = {
    case GetArrivals =>
      sender() ! arrivalTable.selectAll
  }

  override def receive: Receive = testReceive orElse super.receive

  override def handleRemovals(toRemove: Iterable[UniqueArrival]): Future[Done] = {
    super.handleRemovals(toRemove).map { done =>
      if (toRemove.nonEmpty) probe ! RemovalHandled
      done
    }
  }

  override def handleUpdates(toUpdate: Iterable[Arrival]): Future[Done] = {
    super.handleUpdates(toUpdate).map { done =>
      if (toUpdate.nonEmpty) probe ! UpdateHandled
      done
    }
  }
}

class AggregatedArrivalsSpec extends CrunchTestLike with BeforeEach {
  override def before: Any = {
    clearDatabase()
  }

  val table: ArrivalTable = ArrivalTable(defaultAirportConfig.portCode, AggregateDbH2, paxFeedSourceOrder)

  def clearDatabase(): Unit = {
    Try(dropTables())
    createTables()
  }

  def createTables(): Unit = {
    AggregateDbH2.schema.createStatements.toList.foreach { query =>
      Await.ready(AggregateDbH2.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 10.seconds)
    }
  }

  def dropTables(): Unit = {
    AggregateDbH2.schema.dropStatements.toList.reverse.foreach { query =>
      Await.ready(AggregateDbH2.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 10.seconds)
    }
  }

  def aggregatedArrivalsTestActor(actorProbe: ActorRef, arrivalTable: ArrivalTableLike): ActorRef = {
    system.actorOf(Props(classOf[TestAggregatedArrivalsActor], arrivalTable, actorProbe), name = "test-aggregated-arrivals-actor")
  }

  "Given a live arrival " >> {
    "When I inspect the message received by the aggregated arrivals actor " >> {
      "Then I should see no removals and one update " >> {

        val scheduled = "2017-01-01T00:00Z"

        val liveArrival = ArrivalGenerator.live(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Option(21))
        val liveFlights = List(liveArrival)

        val testProbe = TestProbe("arrivals-probe")

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          maybeAggregatedArrivalsActor = Option(aggregatedArrivalsTestActor(testProbe.ref, table))
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

        testProbe.expectMsg(UpdateHandled)

        val arrivalsResult = Await.result(crunch.aggregatedArrivalsActor.ask(GetArrivals)(new Timeout(5.seconds)), 5.seconds) match {
          case ag: AggregatedArrivals => ag
        }

        val expected = AggregatedArrival(liveArrival.toArrival(LiveFeedSource), defaultAirportConfig.portCode.iata)

        arrivalsResult === AggregatedArrivals(Seq(expected))
      }
    }
  }

  "Given an existing arrival which is due to expire and a new live arrival " >> {
    "When I inspect the aggregated arrivals " >> {
      "Then I should see both arrivals, ie the expired arrival is not removed because it's in the past " >> {

        val scheduledExpired = "2017-01-05T00:00Z"
        val scheduled = "2017-01-05T00:01Z"

        val expiredArrival = ArrivalGenerator.live(schDt = scheduledExpired, iata = "BA0022", terminal = T1, totalPax = Option(21)).toArrival(LiveFeedSource)

        table.insertOrUpdateArrival(expiredArrival)

        val liveArrival = ArrivalGenerator.live(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Option(21))
        val liveFlights = List(liveArrival)

        val testProbe = TestProbe("arrivals-probe")

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          expireAfterMillis = 250,
          maybeAggregatedArrivalsActor = Option(aggregatedArrivalsTestActor(testProbe.ref, table))
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

        testProbe.expectMsg(UpdateHandled)

        val arrivalsResult = Await.result(crunch.aggregatedArrivalsActor.ask(GetArrivals)(new Timeout(5.seconds)), 5.seconds) match {
          case ag: AggregatedArrivals => ag.arrivals.toSet
        }

        val expected = Set(
          AggregatedArrival(liveArrival.toArrival(LiveFeedSource), defaultAirportConfig.portCode.iata),
          AggregatedArrival(expiredArrival, defaultAirportConfig.portCode.iata)
        )

        arrivalsResult === expected
      }
    }
  }

  "Given an existing future base arrival followed by an empty list of base arrivals " >> {
    "When I inspect the aggregated arrivals " >> {
      "Then I should see no arrivals" >> {

        val scheduledDescheduled = "2017-01-10T00:00Z"
        val scheduled = "2017-01-05T00:00Z"

        val descheduledArrival = ArrivalGenerator.forecast(schDt = scheduledDescheduled, iata = "BA0022", terminal = T1, totalPax = Option(21))

        table.insertOrUpdateArrival(descheduledArrival.toArrival(LiveFeedSource))

        val testProbe = TestProbe("arrivals-probe")

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          expireAfterMillis = 250,
          maybeAggregatedArrivalsActor = Option(aggregatedArrivalsTestActor(testProbe.ref, table)),
          forecastMaxDays = 10
        ))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(descheduledArrival)))
        testProbe.expectMsg(UpdateHandled)

        val arrivalsResult1 = Await.result(crunch.aggregatedArrivalsActor.ask(GetArrivals)(new Timeout(5.seconds)), 5.seconds) match {
          case ag: AggregatedArrivals => ag.arrivals.toSet
        }

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List()))
        testProbe.expectMsg(RemovalHandled)

        val arrivalsResult2 = Await.result(crunch.aggregatedArrivalsActor.ask(GetArrivals)(new Timeout(5.seconds)), 5.seconds) match {
          case ag: AggregatedArrivals => ag.arrivals.toSet
        }

        arrivalsResult1.size === 1 && arrivalsResult2 === Set()
      }
    }
  }
}
