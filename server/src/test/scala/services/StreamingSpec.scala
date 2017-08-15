package services

import actors.TimeZone
import actors.TimeZone.localTimeZone
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.QueueName
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone, LocalDate}
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.Specification
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable
import scala.collection.immutable.{Iterable, Seq}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Success, Try}

case class FlightSplitMinute(flightId: Int, paxType: PaxType, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

case class QueueLoadMinute(queueName: QueueName, paxLoad: Double, workLoad: Double, minute: Long)

case class QueueMinute(queueName: QueueName, paxLoad: Double, workLoad: Double, crunchDesks: Int, crunchWait: Int, allocStaff: Int, allocWait: Int, minute: Long)

class StreamingSpec extends Specification {
  implicit val system = ActorSystem("reactive-crunch")
  implicit val materializer = ActorMaterializer()

  "Given a flight with one passenger and one split to eea desk " +
    "When I ask for queue loads " +
    "Then I should see a single eea desk queue load containing the passenger and their proc time" >> {
    val scheduled = "2017-01-01T00:00Z"
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes).to(Sink.actorRef(probe.ref, "completed")).run()


    val expected = Set(QueueLoadMinute(Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch))

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
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
      List(ApiSplits(List(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, edPax),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, egPax)
      ), "api", PaxNumbers))))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )

    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes).to(Sink.actorRef(probe.ref, "completed")).run()


    val expected = Set(
      QueueLoadMinute(Queues.EeaDesk, edPax, edPax * emr2dProcTime, scheduledMillis),
      QueueLoadMinute(Queues.EGate, egPax, egPax * emr2eProcTime, scheduledMillis))

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
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
      List(ApiSplits(List(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, edPax),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, egPax)
      ), "api", PaxNumbers))))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )

    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes).to(Sink.actorRef(probe.ref, "completed")).run()


    val expected = Set(
      QueueLoadMinute(Queues.EeaDesk, 20 * edSplit, 20 * edSplit * emr2dProcTime, scheduledMillis),
      QueueLoadMinute(Queues.EGate, 20 * egSplit, 20 * egSplit * emr2eProcTime, scheduledMillis),
      QueueLoadMinute(Queues.EeaDesk, 1 * edSplit, 1 * edSplit * emr2dProcTime, scheduledMillis + 60000),
      QueueLoadMinute(Queues.EGate, 1 * egSplit, 1 * egSplit * emr2eProcTime, scheduledMillis + 60000))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When I ask for queue loads " +
    "Then I should see two eea desk queue loads containing the 2 passengers and their proc time" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ), ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes).to(Sink.actorRef(probe.ref, "completed")).run()

    val expected = Set(
      QueueLoadMinute(Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch),
      QueueLoadMinute(Queues.EeaDesk, 1.0, emr2dProcTime, SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 60000))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When I ask for queue workloads between two times " +
    "Then I should get a map of every minute in the day, with workload in minutes when we have flights" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ), ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 180000

    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes)
      .map(indexQueueWorkloadsByMinute)
      .map(queueMinutesForPeriod(startTime, endTime))
      .to(Sink.actorRef(probe.ref, "completed")).run()

    val expected = Map(
      Queues.EeaDesk -> List(
        (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch, emr2dProcTime),
        (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 60000, emr2dProcTime),
        (SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + 120000, 0)
      )
    )

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When crunch queue workloads between two times " +
    "Then I should get a map queue to map of minute to desk rec" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ), ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (30 * 60000)

    val cancellable = flightsToQueueLoadMinutes(sourceUnderTest, procTimes)
      .map(indexQueueWorkloadsByMinute)
      .map(queueMinutesForPeriod(startTime, endTime))
      .map(queueWorkloadsToCrunchResults)
      .to(Sink.actorRef(probe.ref, "completed")).run()

    val expected = Map(Queues.EeaDesk -> Success(
      OptimizerCrunchResult(
        Vector(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      )
    ))

    probe.expectMsg(expected)
    true
  }

  "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
    "When crunch queue workloads between two times " +
    "Then I should emit workloads and crunch results" >> {
    val scheduled1 = "2017-01-01T00:00Z"
    val scheduled2 = "2017-01-01T00:01Z"
    val flightsWithSplits = List(ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ), ApiFlightWithSplits(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled2),
      List(ApiSplits(
        List(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1d)), "api", PaxNumbers))
    ))
    val emr2dProcTime = 20d / 60
    val emr2eProcTime = 35d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(
      eeaMachineReadableToDesk -> emr2dProcTime,
      eeaMachineReadableToEGate -> emr2eProcTime
    )
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, flightsWithSplits)

    val probe = TestProbe()
    val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
    val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (30 * 60000)

    val indexQueueWorkloadsByMinuteFlow: Flow[Set[QueueLoadMinute], Map[QueueName, Map[MillisSinceEpoch, Load]], NotUsed] = Flow.fromFunction(indexQueueWorkloadsByMinute)
    val queueMinutesForPeriodFlow: Flow[Map[QueueName, Map[MillisSinceEpoch, Load]], Map[QueueName, List[(MillisSinceEpoch, Load)]], NotUsed] = Flow.fromFunction(queueMinutesForPeriod(startTime, endTime))
    val queueWorkloadsToCrunchResultsFlow: Flow[Map[QueueName, List[(MillisSinceEpoch, Load)]], Map[QueueName, Try[OptimizerCrunchResult]], NotUsed] = Flow.fromFunction(queueWorkloadsToCrunchResults)

    val queueWorkloadsFromFlights1: Source[Map[QueueName, List[(MillisSinceEpoch, Load)]], (Cancellable, NotUsed)] = flightsToQueueLoadMinutes(sourceUnderTest, procTimes)
      .via(indexQueueWorkloadsByMinuteFlow)
      .viaMat(queueMinutesForPeriodFlow)(Keep.both)

    val crunchResults: Source[Map[QueueName, Try[OptimizerCrunchResult]], (Cancellable, NotUsed)] = queueWorkloadsFromFlights1
      .via(queueWorkloadsToCrunchResultsFlow)

    val cancellable = crunchResults
      .to(Sink.actorRef(probe.ref, "completed")).run()

    val expected = Map(Queues.EeaDesk -> Success(
      OptimizerCrunchResult(
        Vector(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      )
    ))

    probe.expectMsg(expected)
    true
  }

  //  "Given one queue load minute " +
  //    "When I ask for a crunch result " +
  //    "Then I should get appropriate desk recs" >> {
  //    val scheduled = "2017-01-01T00:00Z"
  //    val queueLoadSets = Source(List(Set(
  //      QueueLoadMinute(Queues.EeaDesk, 1.0, 0.25, SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch)
  //    )))
  //    val workloadFor24Hours = queueLoadSets.map {
  //      case queueLoads =>
  //        val now = new DateTime(SDate(scheduled).millisSinceEpoch)
  //        val start = now.toLocalDate.toDateTimeAtStartOfDay(DateTimeZone.forID("Europe/London")).getMillis
  //        val minutes = List.range(start, start + (1000 * 60 * 60 * 24))
  //    }
  //  }


  def queueWorkloadsToCrunchResults(queuesWorkloads: Map[QueueName, List[(MillisSinceEpoch, Double)]]): Map[QueueName, Try[OptimizerCrunchResult]] =
    queuesWorkloads.mapValues((workloads) => {
      val workloadMinutes = workloads.map(_._2)
      TryRenjin.crunch(workloadMinutes, Seq.fill(workloadMinutes.length)(0), Seq.fill(workloadMinutes.length)(10), OptimizerConfig(25))
    })


  def queueMinutesForPeriod(startTime: Long, endTime: Long)(queue: Map[QueueName, Map[MillisSinceEpoch, Double]]): Map[QueueName, List[(MillisSinceEpoch, Load)]] =
    queue.mapValues(
      queueWorkloadMinutes => List.range(startTime, endTime, 60000).map(minute => {
        (minute, queueWorkloadMinutes.getOrElse(minute, 0d))
      })
    )

  def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[QueueName, Map[MillisSinceEpoch, Double]] =
    queueWorkloadMinutes
      .groupBy(_.queueName)
      .mapValues(_.map(qwl =>
        qwl.minute -> qwl.workLoad
      ).toMap)


  def flightsToQueueLoadMinutes(flightsWithSplitsSource: Source[List[ApiFlightWithSplits], Cancellable], procTimes: Map[PaxTypeAndQueue, Double]) = flightsWithSplitsSource.map {
    case flightsWithSplits =>
      flightsWithSplits.flatMap {
        case ApiFlightWithSplits(flight, splits) =>
          val flightSplitMinutes: immutable.Seq[FlightSplitMinute] = flightToFlightSplitMinutes(flight, splits, procTimes)
          val queueLoadMinutes: immutable.Iterable[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutes)

          queueLoadMinutes
      }.toSet
  }

  def lastLocalMidnightString(millis: Long): String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    // todo this function needs more work to make it a sensible cut off time
    lastLocalMidnight(new DateTime(millis)).toString(formatter)
  }

  def lastLocalMidnight(pointInTime: DateTime): DateTime = {
    TimeZone.lastLocalMidnightOn(pointInTime)
  }

  private def flightsToFlightSplitMinutes(flightsWithSplitsSource: Source[List[ApiFlightWithSplits], NotUsed], procTimes: Map[PaxTypeAndQueue, Double]) = {
    flightsWithSplitsSource.map {
      case flightsWithSplits =>
        flightsWithSplits.flatMap {
          case ApiFlightWithSplits(flight, splits) =>
            val flightSplitMinutes: immutable.Seq[FlightSplitMinute] = flightToFlightSplitMinutes(flight, splits, procTimes)

            flightSplitMinutes
        }
    }
  }


  private def flightToFlightSplitMinutes(flight: Arrival, splits: List[ApiSplits], procTimes: Map[PaxTypeAndQueue, Double]) = {
    val splitsToUse = splits.head
    val totalPax = splitsToUse.splits.map(qc => qc.paxCount).sum
    val splitRatios = splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / totalPax))

    minutesForHours(flight.PcpTime, 1)
      .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
      .flatMap {
        case (minuteMillis, flightPaxInMinute) =>
          splitRatios.map(apiSplitRatio => flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplitRatio))
      }
  }

  private def flightSplitMinute(flight: Arrival, procTimes: Map[PaxTypeAndQueue, Load], minuteMillis: MillisSinceEpoch, flightPaxInMinute: Int, apiSplitRatio: ApiPaxTypeAndQueueCount) = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val splitWorkLoadInMinute = splitPaxInMinute * procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
    FlightSplitMinute(flight.FlightID, apiSplitRatio.passengerType, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
  }

  private def flightSplitMinutesToQueueLoadMinutes(flightSplitMinutes: immutable.Seq[FlightSplitMinute]) = {
    flightSplitMinutes
      .groupBy(s => (s.queueName, s.minute)).map {
      case ((queueName, minute), fsms) =>
        val paxLoad = fsms.map(_.paxLoad).sum
        val workLoad = fsms.map(_.workLoad).sum
        QueueLoadMinute(queueName, paxLoad, workLoad, minute)
    }
  }
}
