package services.inputfeeds

import java.io.File

import actors._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator.apiFlight
import controllers._
import drt.services.AirportConfigHelpers
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{Arrival, _}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}
import services.{SDate, SplitsProvider}

import scala.collection.JavaConversions._
import scala.collection.immutable.{IndexedSeq, Seq}
import scala.concurrent.Await
import scala.concurrent.duration._


object TestCrunchConfig {
  val airportConfig = airportConfigForHours(1)
  val AirportConfigOrigin = "Airport Config"

  def airportConfigForHours(hours: Int) = {
    val seqOfHoursInts = List.fill[Int](hours) _
    AirportConfig(
      portCode = "EDI",
      queues = Map(
        "A1" -> Seq("eeaDesk", "eGate", "nonEeaDesk"),
        "A2" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
      ),
      slaByQueue = Map(
        "eeaDesk" -> 20,
        "eGate" -> 25,
        "nonEeaDesk" -> 45
      ),
      terminalNames = Seq("A1", "A2"),
      timeToChoxMillis = 0L,
      firstPaxOffMillis = 0L,
      defaultWalkTimeMillis = 0L,
      defaultPaxSplits = SplitRatios(
        AirportConfigOrigin,
        SplitRatio(eeaMachineReadableToDesk, 0.4875),
        SplitRatio(eeaMachineReadableToEGate, 0.1625),
        SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
        SplitRatio(visaNationalToDesk, 0.05),
        SplitRatio(nonVisaNationalToDesk, 0.05)
      ),
      defaultProcessingTimes = Map(
        "A1" -> Map(
          eeaMachineReadableToDesk -> 16d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          eeaNonMachineReadableToDesk -> 50d / 60,
          visaNationalToDesk -> 75d / 60,
          nonVisaNationalToDesk -> 64d / 60
        ),
        "A2" -> Map(
          eeaMachineReadableToDesk -> 30d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          eeaNonMachineReadableToDesk -> 50d / 60,
          visaNationalToDesk -> 120d / 60,
          nonVisaNationalToDesk -> 120d / 60
        )),
      minMaxDesksByTerminalQueue = Map(
        "A1" -> Map(
          "eeaDesk" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          "nonEeaDesk" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          "eGate" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25))
        ),
        "A2" -> Map(
          "eeaDesk" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          "nonEeaDesk" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          "eGate" -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25))
        )
      ),
      shiftExamples = Seq(
        "Midnight shift, A1, {date}, 00:00, 00:59, 10",
        "Night shift, A1, {date}, 01:00, 06:59, 4",
        "Morning shift, A1, {date}, 07:00, 13:59, 15",
        "Afternoon shift, A1, {date}, 14:00, 16:59, 10",
        "Evening shift, A1, {date}, 17:00, 23:59,17"
      )
    )
  }

  def levelDbJournalDir(tn: String) = s"target/test/journal/$tn"

  def levelDbTestActorSystem(tn: String) = ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
    "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.journal.leveldb.dir" -> levelDbJournalDir(tn),
    "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
    "akka.persistence.snapshot-store.local.dir" -> s"$tn/snapshot"
  )).withFallback(ConfigFactory.load(getClass.getResource("/application.conf").getPath.toString)))

  case class TestContext(override val system: ActorSystem) extends
    TestKit(system) with ImplicitSender {
    import scala.language.postfixOps
    implicit val timeout: Timeout = Timeout(5 seconds)
  }

  def withContext[T](tn: String = "", timeProvider: () => DateTime = () => DateTime.now())(f: (TestContext) => T): T = {
    val journalDirName = TestCrunchConfig.levelDbJournalDir(tn)

    val journalDir = new File(journalDirName)
    journalDir.mkdirs()
    val context = TestContext(levelDbTestActorSystem(tn))
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    PersistenceCleanup.deleteJournal(journalDirName)
    res
  }
}

class SplitsRequestRecordingCrunchActor(hours: Int, val airportConfig: AirportConfig, timeProvider: () => DateTime = () => DateTime.now(), _splitRatioProvider: (Arrival => Option[SplitRatios]))
  extends AirportConfigHelpers {

  def splitRatioProvider = _splitRatioProvider

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 1d

  def pcpArrivalTimeProvider(flight: Arrival): MilliDate = MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)

  def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
    PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, ArrivalHelper.bestPax)(flight)
}
