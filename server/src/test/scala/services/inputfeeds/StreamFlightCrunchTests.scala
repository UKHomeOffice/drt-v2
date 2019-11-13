package services.inputfeeds

import java.io.File

import actors.PersistenceCleanup
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.services.AirportConfigHelpers
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.TerminalName
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{Arrival, _}
import org.joda.time.DateTime
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount

import scala.collection.JavaConverters._
import scala.collection.immutable.{IndexedSeq, Seq}
import scala.concurrent.duration._
import scala.language.postfixOps


object TestCrunchConfig {
  val airportConfig: AirportConfig = airportConfigForHours(1)
  val AirportConfigOrigin = "Airport Config"

  def airportConfigForHours(hours: Int): AirportConfig = {
    import AirportConfigDefaults._
    val seqOfHoursInts = List.fill[Int](hours) _
    AirportConfig(
      portCode = "EDI",
      queues = Map(
        "A1" -> Seq(Queues.EeaDesk, Queues.EGate, Queues.NonEeaDesk),
        "A2" -> Seq(Queues.EeaDesk, Queues.EGate, Queues.NonEeaDesk)
      ),
      slaByQueue = Map(
        Queues.EeaDesk -> 20,
        Queues.EGate -> 25,
        Queues.NonEeaDesk -> 45
      ),
      terminalNames = Seq("A1", "A2"),
      timeToChoxMillis = 0L,
      firstPaxOffMillis = 0L,
      defaultWalkTimeMillis = Map("A1" -> 0L, "A2" -> 0L),
      terminalPaxSplits = List("A1", "A2").map(t => (t, SplitRatios(
        AirportConfigOrigin,
        SplitRatio(eeaMachineReadableToDesk, 0.4875),
        SplitRatio(eeaMachineReadableToEGate, 0.1625),
        SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
        SplitRatio(visaNationalToDesk, 0.05),
        SplitRatio(nonVisaNationalToDesk, 0.05)
      ))).toMap,
      terminalProcessingTimes = Map(
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
          Queues.EeaDesk -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          Queues.NonEeaDesk -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          Queues.EGate -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25))
        ),
        "A2" -> Map(
          Queues.EeaDesk -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          Queues.NonEeaDesk -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25)),
          Queues.EGate -> Tuple2(seqOfHoursInts(2), seqOfHoursInts(25))
        )
      ),
      shiftExamples = Seq(
        "Midnight shift, A1, {date}, 00:00, 00:59, 10",
        "Night shift, A1, {date}, 01:00, 06:59, 4",
        "Morning shift, A1, {date}, 07:00, 13:59, 15",
        "Afternoon shift, A1, {date}, 14:00, 16:59, 10",
        "Evening shift, A1, {date}, 17:00, 23:59,17"
      ),
      role = STNAccess,
      terminalPaxTypeQueueAllocation = Map("T1" -> defaultQueueRatios)
    )
  }

  def levelDbJournalDir(tn: String) = s"target/test/journal/$tn"

  def levelDbTestActorSystem(tn: String) = ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
    "akka.actor.warn-about-java-serializer-usage" -> false,
    "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.journal.leveldb.dir" -> levelDbJournalDir(tn),
    "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
    "akka.persistence.snapshot-store.local.dir" -> s"$tn/snapshot"
  ).asJava).withFallback(ConfigFactory.load(getClass.getResource("/application.conf").getPath)))

  case class TestContext(override val system: ActorSystem) extends
    TestKit(system) with ImplicitSender {
        implicit val timeout: Timeout = Timeout(5 seconds)
  }

  def withContext[T](tn: String = "", timeProvider: () => DateTime = () => DateTime.now())(f: TestContext => T): T = {
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

class SplitsRequestRecordingCrunchActor(hours: Int, val airportConfig: AirportConfig, timeProvider: () => DateTime = () => DateTime.now(), _splitRatioProvider: Arrival => Option[SplitRatios])
  extends AirportConfigHelpers {

  def splitRatioProvider: Arrival => Option[SplitRatios] = _splitRatioProvider

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 1d

  def pcpArrivalTimeProvider(flight: Arrival): MilliDate = MilliDate(flight.Scheduled)

  def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
    PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, ArrivalHelper.bestPax)(flight)
}
