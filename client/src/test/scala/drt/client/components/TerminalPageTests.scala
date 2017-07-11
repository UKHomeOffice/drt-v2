package drt.client.components

import java.io.StringReader

import autowire.Core.Request
import diode.ModelR
import diode.data.{Pot, Ready}
import drt.client.SPAMain
import drt.client.actions.Actions.{UpdateAirportConfig, UpdateCrunchResult, UpdateWorkloads}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.client.services.{AjaxClient, RootModel, SPACircuit, UpdateFlightsWithSplits}
import drt.client.services.TerminalDeploymentTests.TestAirportConfig
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import japgolly.scalajs.react.component.{Js, Scala}
import japgolly.scalajs.react.component.Scala.{MountedImpure, Unmounted}
import japgolly.scalajs.react.vdom.html_<^
import utest._
import drt.client.logger.log
import drt.client.services.RootModel.TerminalQueueCrunchResults
import japgolly.scalajs.react.internal.Effect.Id
import org.scalajs.dom.{DOMList, Element, Node}
import org.w3c.dom.{Comment, Text}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


object TerminalPageTests extends TestSuite {

  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.vdom.html_<^._
  import japgolly.scalajs.react.{test, _}

  test.WebpackRequire.ReactTestUtils

  import FlightsWithSplitsTable.ArrivalsTable

  def tests = TestSuite{}

  def testsDisabled = TestSuite {


    val testFlight = Arrival(
      Operator = "Op",
      Status = "scheduled",
      SchDT = "2016-01-01T13:00Z",
      EstDT = "2016-01-01T13:05Z",
      ActDT = "2016-01-01T13:10Z",
      EstChoxDT = "2016-01-01T13:15Z",
      ActChoxDT = "2016-01-01T13:20Z",
      Gate = "10",
      Stand = "10A",
      MaxPax = 200,
      ActPax = 150,
      TranPax = 10,
      RunwayID = "1",
      BaggageReclaimId = "A",
      FlightID = 1000,
      AirportID = "LHR",
      Terminal = "T2",
      rawICAO = "BA0001",
      rawIATA = "BAA0001",
      Origin = "JFK",
      PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
    )

    val testFlight2 = Arrival(
      Operator = "EZ",
      Status = "scheduled",
      SchDT = "2016-01-01T13:00Z",
      EstDT = "2016-01-01T13:05Z",
      ActDT = "2016-01-01T13:10Z",
      EstChoxDT = "2016-01-01T13:15Z",
      ActChoxDT = "2016-01-01T13:20Z",
      Gate = "10",
      Stand = "10A",
      MaxPax = 200,
      ActPax = 150,
      TranPax = 10,
      RunwayID = "1",
      BaggageReclaimId = "A",
      FlightID = 1000,
      AirportID = "LHR",
      Terminal = "T2",
      rawICAO = "EZ0001",
      rawIATA = "EZY0001",
      Origin = "JFK",
      PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
    )

    def withSplits(flights: Seq[Arrival]) = {
      FlightsWithSplits(flights.map(ApiFlightWithSplits(_, Nil)).toList)
    }

    val airportConfig = AirportConfig(
      portCode = "STN",
      queues = Map("T1" -> Seq("eeaDesk", "nonEeaDesk", "eGate")),
      slaByQueue = Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20),
      terminalNames = Seq("T1"),
      defaultPaxSplits = SplitRatios(
        TestAirportConfig,

        List(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.4875))),
      defaultProcessingTimes = Map(),
      minMaxDesksByTerminalQueue = Map("T1" -> Map(
        "eeaDesk" -> (List.fill[Int](24)(1), List.fill[Int](24)(20)),
        "nonEeaDesk" -> (List.fill[Int](24)(1), List.fill[Int](24)(20)),
        "eGate" -> (List.fill[Int](24)(1), List.fill[Int](24)(20))
      ))
    )

    /** *
      * These tests are intended only as simple regression test guards against inadvertent mistakes
      * We should expect them to be very free to change, the age old balance between brittle, and flexibility
      */

    "TerminalPage regression tests" - {
      "Given an empty page" +
        "When we render a terminal page we see ?? " - {
        val expected = <.div()

        val page = staticComponent(TerminalPage(terminalName = "T2", MockRouterCtl()))()

        ReactTestUtils.withRenderedIntoDocument(page) {
          real =>
            assert(real.outerHtmlScrubbed() == """<div><div><h2>In the next 3 hours</h2></div><div></div></div>""")
        }
        //        assertRenderedComponentsAreEqual(page, static)
      }
      "Given now is 2016-01-01T13:00Z" +
        "AND 2 flights arriving after 2016-01-01T13:00Z to terminal T2" +
        "When we render 'T2' terminal page we see Big Summary Boxes with correct flight numbers " - {
        val realPage = Try {
          val parsedDate = SDate.parse("2016-01-01T13:00Z")

          val sdate = () => {
            parsedDate
          }
          SDate.monkeyPatchNow(sdate)

          SPACircuit.dispatch(UpdateFlightsWithSplits(withSplits(testFlight :: testFlight2 :: Nil)))
          staticComponent(TerminalPage(terminalName = "T2", MockRouterCtl()))()
        } match {
          case Failure(f) =>
            log.error(s"failure $f", f.asInstanceOf[Exception])
            assert(false)
          case Success(realPage) =>
            val nbsp = "\u00a0"
            val expected = <.div(
              <.div(<.h2("In the next 3 hours"),
                <.span(
                  <.div(
                    <.div(^.className := "summary-boxes ",
                      <.div(^.className := "summary-box-container",
                        <.h3(
                          <.span(^.className := "summary-box-count flight-count", "2 "),
                          <.span(^.className := "sub", "Flights"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(<.span(^.className := "summary-box-count best-pax-count", "300 "),
                          <.span(^.className := "sub", "Best Pax"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(nbsp, <.span(
                          <.div(^.className := "summary-box-count best-pax-count split-graph-container splitsource-aggregated",
                            <.div(^.title :=
                              """Total: 0
                                |0 EeaMachineReadable > eGate
                                |0 EeaMachineReadable > eeaDesk
                                |0 EeaNonMachineReadable > eeaDesk
                                |0 VisaNational > nonEeaDesk
                                |0 NonVisaNational > nonEeaDesk""".stripMargin('|'),
                              ^.className := "splits",
                              <.div(^.className := "graph",
                                <.div(^.title := "0 EeaMachineReadable > eGate", ^.className := "bar eGate"),
                                <.div(^.title := "0 EeaMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                <.div(^.title := "0 EeaNonMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                <.div(^.title := "0 VisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"),
                                <.div(^.title := "0 NonVisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"))))))))))),
              <.div())
            val static = staticComponent(expected)()
            withRenderedComponents(rc = realPage, expected = static) {
              (real, simple) => {
                val (actualHtml: String, simpleHtml: String) = selectComponents(real, simple, ".summary-boxes")
                if (actualHtml != simpleHtml) {
                  val arract = actualHtml.toString.toCharArray.toList
                  val arrsim = simpleHtml.toString.toCharArray.toList
                  val diffs: Seq[Int] = arract.zip(arrsim).zipWithIndex.collect {
                    case ((act, sim), index) if (act != sim) => index
                  }
                  for {index <- diffs} {
                    println(s"$index ${actualHtml.drop(index - 10).take(60)}")
                    println(s"$index ${actualHtml.drop(index - 10).take(60)}")
                  }
                  println(s"act: $actualHtml")
                  println(s"sim: $simpleHtml")
                  println(s"actual: ${arract}")
                  println(s"simple: $arrsim")
                }
                assert(actualHtml == simpleHtml)
              }
            }
        }
      }
      "Given 2 flights" +
        "AND splits on those flights" +
        "AND some workloads" +
        "WHEN we render a terminal page" +
        "THEN we see ?? " - {
        val realPage = Try {
          val parsedDate = SDate.parse("2016-01-01T13:00Z")

          println(s"parseddate $parsedDate")
          val sdate = () => {
            parsedDate
          }
          println(s"date will be ${sdate()}")
          SDate.monkeyPatchNow(sdate)
          import boopickle.Default._

          val paxLoadsAndWorkloads = Map("T2" ->
            Map(Queues.EeaDesk -> (
              Seq(WL(parsedDate.millisSinceEpoch, 15)),
              Seq(Pax(parsedDate.millisSinceEpoch, 10)))))


          val impl = (request: AjaxClient.Request) => {
            println(s"mocked ajax request $request")
            val Path = "drt/shared/Api/airportInfosByAirportCodes".split("/").toSeq
            request match {
              case Request(Path, _) =>
                Future.successful(Pickle.intoBytes(Map[String, AirportInfo]()))
              case Request(scala.Seq("drt", "shared", "Api", "getWorkloads"), _) =>
                Future.successful(Pickle.intoBytes(paxLoadsAndWorkloads))
              case unknownRequest =>
                log.error(s"testing has not mocked $unknownRequest")
                Future.failed(new Exception(s"Should have mocked $request"))
            }
          }

          AjaxClient.monkeyPatchDoCallImpl(impl)

          val splits = ApiSplits(List(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 0.5),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 0.5)),
            SplitSources.Historical, Percentage) :: Nil

          SPACircuit.dispatch(UpdateFlightsWithSplits(
            FlightsWithSplits(
              List(
                ApiFlightWithSplits(testFlight, splits),
                ApiFlightWithSplits(testFlight2, splits)))))

          SPACircuit.dispatch(UpdateWorkloads(paxLoadsAndWorkloads))

          staticComponent(TerminalPage(terminalName = "T2", MockRouterCtl()))()
        } match {
          case Failure(f) =>
            log.error(s"failure $f", f.asInstanceOf[Exception])
            assert(false)
          case Success(realPage) =>
            val nbsp = "\u00a0"
            val expected = <.div(
              <.div(<.h2("In the next 3 hours"),
                <.span(
                  <.div(
                    <.div(^.className := "summary-boxes ",
                      <.div(^.className := "summary-box-container",
                        <.h3(
                          <.span(^.className := "summary-box-count flight-count", "2 "),
                          <.span(^.className := "sub", "Flights"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(<.span(^.className := "summary-box-count best-pax-count", "300 "),
                          <.span(^.className := "sub", "Best Pax"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(nbsp, <.span(
                          <.div(^.className := "summary-box-count best-pax-count split-graph-container splitsource-aggregated",
                            <.div(^.title :=
                              """Total: 0
                                |0 EeaMachineReadable > eGate
                                |0 EeaMachineReadable > eeaDesk
                                |0 EeaNonMachineReadable > eeaDesk
                                |0 VisaNational > nonEeaDesk
                                |0 NonVisaNational > nonEeaDesk""".stripMargin('|'),
                              ^.className := "splits",
                              <.div(^.className := "graph",
                                <.div(^.title := "0 EeaMachineReadable > eGate", ^.className := "bar eGate"),
                                <.div(^.title := "0 EeaMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                <.div(^.title := "0 EeaNonMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                <.div(^.title := "0 VisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"),
                                <.div(^.title := "0 NonVisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"))))))))))),
              <.div())
            val staticExpected = staticComponent(expected)()

            withRenderedComponents(rc = realPage, expected = staticExpected) {
              (real, simple) => {
                assertComponentsAreSame(real, simple, ".summary-boxes")

              }
            }
        }
      }
      """Given An Airport Config
        AND 2 flights with Historical Splits
        AND some workloads
        And some queueCrunchResults
        WHEN we render a terminal page
        THEN we see ?? """ - {
        val realPage = Try {
          val parsedDate = SDate.parse("2016-01-01T13:00Z")

          println(s"parseddate $parsedDate")
          val sdate = () => {
            parsedDate
          }
          println(s"date will be ${sdate()}")
          SDate.monkeyPatchNow(sdate)
          import boopickle.Default._

          val twentyFourHoursOfMinutes = WorkloadsHelpers.minutesForPeriod(parsedDate.millisSinceEpoch, 24)
          val paxLoadsAndWorkloads = Map("T2" ->
            Map(Queues.EeaDesk -> (
              twentyFourHoursOfMinutes.map(t => WL(t, 15)),
              twentyFourHoursOfMinutes.map(t => Pax(t, 15)))))
          log.info(s"paxloads are nun $paxLoadsAndWorkloads")
          val crunchResult = CrunchResult(parsedDate.millisSinceEpoch, 1000 * 15,
            recommendedDesks = Vector.fill(1440)(10),
            waitTimes = List.fill(1440)(23)
          )

          val impl = (request: AjaxClient.Request) => {
            println(s"mocked ajax request $request")
            Try {
              val params = request.args.mapValues(v => v.array().map(_.toChar))
              println(s"request params => $params")
            }
            val Path = "drt/shared/Api/airportInfosByAirportCodes".split("/").toSeq
            request match {
              case Request(Path, _) =>
                Future.successful(Pickle.intoBytes(Map[String, AirportInfo]()))
              case Request(scala.Seq("drt", "shared", "Api", "getWorkloads"), _) =>
                Future.successful(Pickle.intoBytes(paxLoadsAndWorkloads))
              case Request(scala.Seq("drt", "shared", "Api", "getLatestCrunchResult"), _) =>
                log.info(s"----> pickling crunchresult into a right")
                val bytes = Pickle.intoBytes(Right(crunchResult))
                log.info(s"----!!! pickled into a right")
                Future.successful(bytes)
              case unknownRequest =>
                log.error(s"testing has not mocked $unknownRequest")
                Future.failed(new Exception(s"Should have mocked $request"))
            }
          }

          AjaxClient.monkeyPatchDoCallImpl(impl)

          SPACircuit.dispatch(UpdateAirportConfig(AirportConfigs.lhr))

          val splits = ApiSplits(List(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 0.5),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 0.5)),
            SplitSources.Historical, Percentage) :: Nil

          SPACircuit.dispatch(UpdateFlightsWithSplits(
            FlightsWithSplits(
              List(
                ApiFlightWithSplits(testFlight, splits),
                ApiFlightWithSplits(testFlight2, splits)))))

          SPACircuit.dispatch(UpdateWorkloads(paxLoadsAndWorkloads))


          SPACircuit.dispatch(UpdateCrunchResult("T2", Queues.EeaDesk, crunchResult))
          val zoomed: ModelR[RootModel, TerminalQueueCrunchResults] = SPACircuit.zoom(rm => {
            println(s"zoomed in and crunchResults are ${rm.queueCrunchResults}")
            rm.queueCrunchResults
          })
          println(s"zoomed again: ${zoomed()}")
          staticComponent(TerminalPage(terminalName = "T2", MockRouterCtl()))()
        } match {
          case Failure(f) =>
            log.error(s"failure $f", f.asInstanceOf[Exception])
            assert(false)
          case Success(realPage) =>
            val nbsp = "\u00a0"
            val expected = <.div(
              <.div(
                <.h2("In the next 3 hours"),
                <.span(
                  <.div(
                    <.div(^.className := "summary-boxes ",
                      <.div(^.className := "summary-box-container",
                        <.h3(
                          <.span(^.className := "summary-box-count flight-count", "2 "),
                          <.span(^.className := "sub", "Flights"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(<.span(^.className := "summary-box-count best-pax-count", "300 "),
                          <.span(^.className := "sub", "Best Pax"))),
                      <.div(^.className := "summary-box-container",
                        <.h3(nbsp,
                          <.span(
                            <.div(^.className := "summary-box-count best-pax-count split-graph-container splitsource-aggregated",
                              <.div(
//                                <div class="splits"><div class="splits-tooltip"><div><table class="table table-responsive table-striped table-hover table-sm "><tbody><tr><td>EeaMachineReadable</td><td>eGate</td><td>0</td></tr><tr><td>EeaMachineReadable</td><td>eeaDesk</td><td>1</td></tr><tr><td>EeaNonMachineReadable</td><td>eeaDesk</td><td>0</td></tr><tr><td>VisaNational</td><td>nonEeaDesk</td><td>0</td></tr><tr><td>NonVisaNational</td><td>nonEeaDesk</td><td>1</td></tr><tr><td>VisaNational</td><td>fastTrack</td><td>0</td></tr><tr><td>NonVisaNational</td><td>fastTrack</td><td>0</td></tr></tbody></table></div></div>,
                                ^.className := "splits",
                                <.div(^.className := "graph",
                                  <.div(^.title := "0 EeaMachineReadable > eGate", ^.className := "bar eGate"),
                                  <.div(^.title := "0 EeaMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                  <.div(^.title := "0 EeaNonMachineReadable > eeaDesk", ^.className := "bar eeaDesk"),
                                  <.div(^.title := "0 VisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"),
                                  <.div(^.title := "0 NonVisaNational > nonEeaDesk", ^.className := "bar nonEeaDesk"))))))))))),
              <.div())
            val staticExpected = staticComponent(expected)()

            withRenderedComponents(rc = realPage, expected = staticExpected) {
              (real, simple) => {

                val html: Id[Element] = real.getDOMNode
                val prettyHtml = html.outerHTML
                println(s"the whole page was.. ${prettyHtml}")
//                assertComponentsAreSame(real, simple, ".summary-boxes")
                //                assertComponentsAreSame(real, simple, ".summary-boxes")
              }
            }
        }
      }


      def date(dt: String) = <.td(flightDate(dt))

      def flightDate(dt: String) = <.span(^.title := dt.replace("T", " "), dt.split("T")(1))
    }
  }

  implicit class NodeListSeq[T <: Node](nodes: DOMList[T]) extends IndexedSeq[T] {
    override def foreach[U](f: (T) => U): Unit = {
      for (i <- 0 until nodes.length) {
        f(nodes(i))
      }
    }

    override def length: Int = nodes.length

    override def apply(idx: Int): T = nodes(idx)
  }



  def prettyPrintXml(s: String, depth: Int = 0): String = {
    val closing = "></"
    val open = "<"

    var i = 0
    var acc = ""
    var d = 0
    while (i < s.length) {
      val curr = s.slice(i, i+4)
      if (curr.startsWith(closing)) {
        val ind = Seq.fill(d)(" ")
        acc += s">\n$ind</"
        i += 3
        d -= 1
      } else if (curr.startsWith(open)) {
        d += 1
        val ind = Seq.fill(d)(" ")
        acc += s"\n$ind"
      } else {
        acc += curr.charAt(0)
        i += 1
      }
    }
    acc
  }


  def prettyPrint(node: Element, depth: Int = 0): String = {
    val tabs = Seq.fill(depth)(' ').mkString
    val leader = "\n" + tabs
    if (node.hasChildNodes()) {
      val children = node.childNodes
      val cs = children.map {
        case c: Element => prettyPrint(c, depth + 1)
        case c => ""
      }
      leader + cs.mkString
    } else {
      val s = node match {
        case e: Element => "e: " + e.outerHTML
        case c => ""
      }
      leader + s
    }
  }

  private def assertComponentsAreSame(real: MountedImpure[Unit, Unit, Unit], simple: MountedImpure[Unit, Unit, Unit], selector: String) = {
    val (actualSummaryBoxes: String, staticSummaryBoxes: String) = selectComponents(real, simple, selector)
    printComponentDiffs(actualSummaryBoxes, staticSummaryBoxes)
    println(s"asserting in test with splits $actualSummaryBoxes")
    assert(actualSummaryBoxes == staticSummaryBoxes)
  }

  private def selectComponents(real: MountedImpure[Unit, Unit, Unit], simple: MountedImpure[Unit, Unit, Unit], selector: String) = {
    val actualHtml = selectScrubbed(real, selector)
    val simpleHtml = selectScrubbed(simple, selector)
    (actualHtml, simpleHtml)
  }

  private def printComponentDiffs(actualHtml: String, simpleHtml: String) = {
    if (actualHtml != simpleHtml) {
      val arract = actualHtml.toString.toCharArray.toList
      val arrsim = simpleHtml.toString.toCharArray.toList
      val diffs: Seq[Int] = arract.zip(arrsim).zipWithIndex.collect {
        case ((act, sim), index) if act != sim => index
      }
      for {index <- diffs} {
        val from = index - 10
        println(s"$index ${simpleHtml.slice(from, from + 60)}")
        println(s"$index ${actualHtml.slice(from, from + 60)}")
      }
      println(s"act: $actualHtml")
      println(s"sim: $simpleHtml")
      println(s"actual: ${arract}")
      println(s"simple: $arrsim")
    }
  }

  def withRenderedComponents[P](rc: Unmounted[P, Unit, Unit], expected: Unmounted[Unit, Unit, Unit])
                               (f: (MountedImpure[P, Unit, Unit], MountedImpure[Unit, Unit, Unit]) => Unit) = {
    ReactTestUtils.withRenderedIntoDocument(rc) {
      real =>
        ReactTestUtils.withRenderedIntoDocument(expected) {
          simple =>
            f(real, simple)
        }
    }
  }

  private def selectScrubbed[P](real: _root_.japgolly.scalajs.react.component.Scala.MountedImpure[P, Unit, Unit], selector: String) = {
    ReactTestUtils.removeReactInternals(real.getDOMNode.querySelector(selector).outerHTML)
  }

  def staticComponent(staticVdomElement: => html_<^.VdomElement) = {
    ScalaComponent.builder[Unit]("Expected")
      .renderStatic(
        staticVdomElement
      ).build
  }
}