package actors

import actors.PortStateMessageConversion._
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.mutable.Specification
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchStateSnapshotMessage, StaffMinuteMessage}

import scala.collection.immutable.SortedMap

class PortStateMessageConversionSpec extends Specification {
  "Given a persisted port state message containing some valid & invalid crunch and staff minutes " +
    "When I ask for the corresponding PortState " +
    "Then I should only see the valid crunch & staff minutes" >> {
    val validMinuteMilli = 0L
    val invalidMinuteMilli = 60001L
    val crunchMinutes = Seq(
      CrunchMinuteMessage(Option("T1"), Option(Queues.EeaDesk.toString), Option(validMinuteMilli), Option(0), Option(0), Option(0), Option(0), None, None, None, None),
      CrunchMinuteMessage(Option("T1"), Option(Queues.EeaDesk.toString), Option(invalidMinuteMilli), Option(0), Option(0), Option(0), Option(0), None, None, None, None)
    )
    val staffMinutes = Seq(
      StaffMinuteMessage(Option("T1"), Option(validMinuteMilli), Option(0), Option(0), Option(0), None),
      StaffMinuteMessage(Option("T1"), Option(invalidMinuteMilli), Option(0), Option(0), Option(0), None)
    )
    val stateM = PortStateMutable.empty
    snapshotMessageToState(CrunchStateSnapshotMessage(None, None, Seq(), crunchMinutes, staffMinutes), None, stateM)

    val state = stateM.immutable

    val expectedCrunchMinutes = SortedMap[TQM, CrunchMinute]() ++ Seq(CrunchMinute(T1, Queues.EeaDesk, validMinuteMilli, 0, 0, 0, 0, None, None, None, None, None)).map(m => (m.key, m))
    val expectedStaffMinutes = SortedMap[TM, StaffMinute]() ++ Seq(StaffMinute(T1, validMinuteMilli, 0, 0, 0, None)).map(m => (m.key, m))

    val expected = PortState(SortedMap[UniqueArrival, ApiFlightWithSplits](), expectedCrunchMinutes, expectedStaffMinutes)

    state === expected
  }
}
