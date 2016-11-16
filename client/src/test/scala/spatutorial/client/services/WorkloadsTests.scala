package spatutorial.client.services

import diode.data._
import spatutorial.client.components.TableTerminalDeskRecs.{QueueDetailsRow, TerminalUserDeskRecsRow}
import spatutorial.shared.FlightsApi._
import spatutorial.shared._
import utest._
import scala.scalajs.js.Date
import scala.collection.immutable.{IndexedSeq, Map, Seq}

object WorkloadsTests extends TestSuite {

  import spatutorial.client.TableViewUtils._

  val s1 = 1000

  def tests = TestSuite {

    "Given workloads, " - {
      "we need a label per minute" - {
        val firstTime = Date.parse("2016-11-01T07:20Z").toLong
        val workloads = Workloads(
          Map("T1" ->
            Map("eeaDesk" ->
              (List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.take(5)
        assert(labels  == List(
          "07:20",
          "07:21",
          "07:22",
          "07:23",
          "07:24"
        ))
      }
      "it doesn't matter what terminal we have in workloads we need a label per minute" - {
        val firstTime = Date.parse("2016-11-01T07:20Z").toLong
        val workloads = Workloads(
          Map("A1" ->
            Map("eeaDesk" ->
              (List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.take(5)
        assert(labels  == List(
          "07:20",
          "07:21",
          "07:22",
          "07:23",
          "07:24"
        ))
      }
      "the labels are in 24H format" - {
        val firstTime = Date.parse("2016-11-01T14:20Z").toLong
        val workloads = Workloads(
          Map("A1" ->
            Map("eeaDesk" ->
              (List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.take(5)
        assert(labels  == List(
          "14:20",
          "14:21",
          "14:22",
          "14:23",
          "14:24"
        ))
      }
    }
  }
}
