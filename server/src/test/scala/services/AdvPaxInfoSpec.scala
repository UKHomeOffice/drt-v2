package services


import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.actor.{ActorSystem, Cancellable}
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, StreamConverters}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.testkit.TestKit
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.S3ClientOptions
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import controllers.ArrivalGenerator
import drt.server.feeds.chroma.{ChromaFlightFeed, ProdChroma}
import drt.shared.FlightsApi.Flights
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.PassengerSplits.VoyagePaxSplits
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.core.PassengerQueueCalculator
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.Crunch._
import services.inputfeeds.TestCrunchConfig
import services.inputfeeds.TestCrunchConfig.AirportConfigOrigin

import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success



class AdvPaxInfoSpec()
  extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
    with SpecificationLike {
  isolated
  sequential

  //  implicit val materializer = ActorMaterializer()
  val log = LoggerFactory.getLogger(getClass)

  //  "I can get a list of API files from Atmos" >> {
  //    val bucket = "drtdqprod"
  //    val atmosHost = "cas00003.skyscapecloud.com:8443"
  //
  //    val json: Source[VoyageManifest, NotUsed] = AdvPaxInfo(atmosHost, bucket).jsonFiles("drt_dq_170908_120000_0000.zip")
  //
  //    Await.result(json.runWith(Sink.seq), 600 seconds).foreach {
  //      case (zipName, fileName, Success(vpm)) => log.info(s"$fileName from $zipName: $vpm")
  //    }
  //
  //    true === true
  //  }

  "I can run a 2 inlet graph stage" >> {

    implicit val materializer = ActorMaterializer()
    val chroma = ChromaFlightFeed(system.log, ProdChroma(system))
    val flightsSource = chroma.chromaVanillaFlights().map(Flights(_))
    //    val fakeFlightsSource = Source(List(Flights(List(ArrivalGenerator.apiFlight(1)))))
    //    val fakeManifestsSource = Source(List(VoyageManifest("DC", "LHR", "JFK", "0011", "BA", "2016-01-01", "T00:00", List())))
    val bucket = "drtdqprod"
    val atmosHost = "cas00003.skyscapecloud.com:8443"
    val manifestsSource = AdvPaxInfo(atmosHost, bucket).manifests("drt_dq_170911_000000_0000.zip")

    val ac = airportConfig

    def crunchFlow = new CrunchFlow(
      ac.slaByQueue,
      ac.minMaxDesksByTerminalQueue,
      ac.defaultProcessingTimes.head._2,
      CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      ac.terminalNames.toSet)

    val result = RunnableCrunchGraph(flightsSource, manifestsSource, crunchFlow, ac.defaultPaxSplits).run()
    //    val result = GraphStuff.rgSingle(fakeFlightsSource).run()

    Thread.sleep(300000)

    1 === 1
  }

  val airportConfig = AirportConfig(
    portCode = "STN",
    queues = Map("T1" -> Seq("eeaDesk", "eGate", "nonEeaDesk")),
    slaByQueue = Map("eeaDesk" -> 20, "eGate" -> 25, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1"),
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
    shiftExamples = Seq()
  )

  def seqOfHoursInts(value: Int) = List.fill[Int](24)(value)

}