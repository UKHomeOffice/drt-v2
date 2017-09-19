package drt.client.services

import drt.shared.FlightsApi._
import drt.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map}
import scala.scalajs.js.Date

object WorkloadsTests extends TestSuite {

  def tests = TestSuite {

    "Given workloads, " - {
      "we need a label per minute, starting at midnight of today" - {
        val firstTime = Date.parse("2016-11-01T07:20Z").toLong
        val workloads = Workloads(
          Map("T1" ->
            Map("eeaDesk" ->
              Tuple2(List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.take(5)
        assert(labels  == List(
          "00:00",
          "00:01",
          "00:02",
          "00:03",
          "00:04"
        ))
      }
      "it doesn't matter what terminal we have in workloads we need a label per minute" - {
        val firstTime = Date.parse("2016-11-01T07:20Z").toLong
        val workloads = Workloads(
          Map("A1" ->
            Map("eeaDesk" ->
              Tuple2(List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.take(5)
        assert(labels  == List(
          "00:00",
          "00:01",
          "00:02",
          "00:03",
          "00:04"
        ))
      }
      "the labels are in 24H format" - {
        val firstTime = Date.parse("2016-11-01T14:20Z").toLong
        val workloads = Workloads(
          Map("A1" ->
            Map("eeaDesk" ->
              Tuple2(List(WL(firstTime, 99)), List(Pax(firstTime, 10))))))
        val labels: IndexedSeq[TerminalName] = workloads.labels.slice(800, 805)
        assert(labels  == List(
          "13:20", "13:21", "13:22", "13:23", "13:24"
        ))
      }
    }
  }
}
