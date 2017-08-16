package services

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import org.joda.time.DateTimeZone
import org.specs2.mutable.Specification
import services.Crunch._

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.util.Success


//case class QueueMinute(queueName: QueueName, paxLoad: Double, workLoad: Double, crunchDesks: Int, crunchWait: Int, allocStaff: Int, allocWait: Int, minute: Long)

class StreamingSpec extends Specification {
  implicit val system = ActorSystem("reactive-crunch")
  implicit val materializer = ActorMaterializer()

  "Given a flight with one passenger and one split to eea desk " +
    "When I ask for queue loads " +
    "Then I should see a single eea desk queue load containing the passenger and their proc time" >> {
    val scheduled = "2017-01-01T00:00Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)

    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val cancellable = sourceUnderTest.map(flightsToQueueLoadMinutes(procTimes))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Set(QueueLoadMinute("T1", Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch))

    probe.expectMsg(expected)
    cancellable.cancel()

    true
  }

  "Given a flight with one passenger and splits to eea desk & egates " +
    "When I ask for queue loads " +
    "Then I should see 2 queue loads, each representing their portion of the passenger and the split queue" >> {
    val scheduled = "2017-01-01T00:00Z"
    val scheduledMillis = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch
    val edPax = 0.25
    val egPax = 0.75
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
        List(ApiSplits(List(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, edPax),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, egPax)
        ), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)

    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()

    sourceUnderTest.map(flightsToQueueLoadMinutes(procTimes))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Set(
      QueueLoadMinute("T1", Queues.EeaDesk, edPax, edPax * emr2dProcTime, scheduledMillis),
      QueueLoadMinute("T1", Queues.EGate, egPax, egPax * emr2eProcTime, scheduledMillis))

    probe.expectMsg(expected)
    true
  }

  "Given a flight with 21 passengers and splits to eea desk & egates " +
    "When I ask for queue loads " +
    "Then I should see 4 queue loads, 2 for the first 20 pax to each queue and 2 for the last 1 split to each queue" >> {
    val scheduled = "2017-01-01T00:00Z"
    val scheduledMillis = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch
    val totalPax = 21
    val edSplit = 0.25
    val egSplit = 0.75
    val edPax = edSplit * totalPax
    val egPax = egSplit * totalPax
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
        List(ApiSplits(List(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, edPax),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, egPax)
        ), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)

    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    sourceUnderTest.map(flightsToQueueLoadMinutes(procTimes))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Set(
      QueueLoadMinute("T1", Queues.EeaDesk, 20 * edSplit, 20 * edSplit * emr2dProcTime, scheduledMillis),
      QueueLoadMinute("T1", Queues.EGate, 20 * egSplit, 20 * egSplit * emr2eProcTime, scheduledMillis),
      QueueLoadMinute("T1", Queues.EeaDesk, 1 * edSplit, 1 * edSplit * emr2dProcTime, scheduledMillis + 60000),
      QueueLoadMinute("T1", Queues.EGate, 1 * egSplit, 1 * egSplit * emr2eProcTime, scheduledMillis + 60000))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When I ask for queue loads " +
    "Then I should see two eea desk queue loads containing the 2 passengers and their proc time" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))),
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()

    sourceUnderTest.map(flightsToQueueLoadMinutes(procTimes))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Set(
      QueueLoadMinute("T1", Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch),
      QueueLoadMinute("T1", Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 60000))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When I ask for queue workloads between two times " +
    "Then I should get a map of every minute in the day, with workload in minutes when we have flights" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))),
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 120000

    sourceUnderTest
      .map(flightsToQueueLoadMinutes(procTimes))
      .map(indexQueueWorkloadsByMinute)
      .map(queueMinutesForPeriod(startTime, endTime))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Map(
      "T1" -> Map(
        Queues.EeaDesk -> List(
          (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch, emr2dProcTime),
          (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 60000, emr2dProcTime),
          (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 120000, 0))))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When crunch queue workloads between two times " +
    "Then I should get a map queue to map of minute to desk rec" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))),
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
        List(ApiSplits(
          List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime)
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * 60000)

    val slaByQueue = Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20)
    val minMaxDesks = Map("T1" -> Map(
      "eeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "nonEeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "eGate" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))

    sourceUnderTest
      .map(flightsToQueueLoadMinutes(procTimes))
      .map(indexQueueWorkloadsByMinute)
      .map(queueMinutesForPeriod(startTime, endTime))
      .map(pwl => queueWorkloadsToCrunchResults(pwl, slaByQueue, minMaxDesks))
      .to(Sink.actorRef(probe.ref, "completed"))
      .run()

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Success(
        OptimizerCrunchResult(
          Vector(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        ))))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When crunch queue workloads between two times " +
    "Then I should emit a CrunchState" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))),
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60
    )

    val slaByQueue = Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20)
    val minMaxDesks = Map("T1" -> Map(
      "eeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "nonEeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "eGate" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * 60000)

    val publisher: Publisher = Publisher(probe.ref, Crunch.Props(probe.ref, slaByQueue, minMaxDesks, procTimes, startTime, endTime))
    publisher.publish(flightsWithSplits)

    val zeroMinutes = (1483228920000L to 1483230540000L by 60000).toList
    val zeroLoads = zeroMinutes.map(minute => (minute, 0.0))
    val workloads = (1483228800000L, 0.3333333333333333) :: (1483228860000L, 0.3333333333333333) :: zeroLoads
    val expected = CrunchState(
      flightsWithSplits,
      Map("T1" -> Map(Queues.EeaDesk -> workloads)),
      Map("T1" -> Map(Queues.EeaDesk -> Success(OptimizerCrunchResult(
        Vector(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))))))

    probe.expectMsg(expected)

    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When crunching a whole day " +
    "Then I should emit a CrunchState containing the right flights, workload minutes and crunch minutes" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))),
      ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))

    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60
    )

    val slaByQueue = Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20)
    val minMaxDesks = Map("T1" -> Map(
      "eeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "nonEeaDesk" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      "eGate" -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))

    val probe = TestProbe()
    val startTime = SDate("2017-01-01T00:00Z", DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate("2017-01-01T23:59Z", DateTimeZone.UTC).millisSinceEpoch

    val publisher: Publisher = Publisher(probe.ref, Crunch.Props(probe.ref, slaByQueue, minMaxDesks, procTimes, startTime, endTime))
    publisher.publish(flightsWithSplits)

    val result = probe.expectMsgAnyClassOf(classOf[CrunchState])

    val resultSummary = result match {
      case CrunchState(flights, workloads, crunchResult) =>
        val workloadCount = workloads.map {
          case (_, twl) => twl.map {
            case (_, qwl) => qwl.length
          }.sum
        }.sum
        val successfulCrunchCount = crunchResult.map {
          case (_, twl) => twl.map {
            case (_, Success(qwl)) => 1
            case _ => 0
          }.sum
        }.sum
        (flights, workloadCount, successfulCrunchCount)
    }

    val expected = (flightsWithSplits, 1440, 1)

    resultSummary === expected
  }
}
