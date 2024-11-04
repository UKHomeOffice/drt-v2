package actors.serializers

import actors.serializers.PortStateMessageConversion._
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchStateSnapshotMessage, StaffMinuteMessage}
import uk.gov.homeoffice.drt.time.MilliDate

import scala.collection.immutable.SortedMap

class PortStateMessageConversionSpec extends Specification {
  "Given a persisted port state message containing some valid & invalid crunch and staff minutes " >> {
    "When I ask for the corresponding PortState " >> {
      "Then I should see the invalid minute (non-minute boundary) converted to valid crunch & staff minutes" >> {
        val validMinuteMilli = 0L
        val invalidMinuteMilli = 60001L
        val crunchMinutes = Seq(
          CrunchMinuteMessage(Option("T1"), Option(Queues.EeaDesk.toString), Option(validMinuteMilli), Option(1), Option(0), Option(0), Option(0), None, None, None, None),
          CrunchMinuteMessage(Option("T1"), Option(Queues.EeaDesk.toString), Option(invalidMinuteMilli), Option(2), Option(0), Option(0), Option(0), None, None, None, None)
        )
        val staffMinutes = Seq(
          StaffMinuteMessage(Option("T1"), Option(validMinuteMilli), Option(1), Option(0), Option(0), None),
          StaffMinuteMessage(Option("T1"), Option(invalidMinuteMilli), Option(2), Option(0), Option(0), None)
        )
        val state = snapshotMessageToState(CrunchStateSnapshotMessage(None, None, Seq(), crunchMinutes, staffMinutes), None)

        val correctedMillis = MilliDate(invalidMinuteMilli).millisSinceEpoch
        val expectedCrunchMinutes = SortedMap[TQM, CrunchMinute]() ++ Seq(
          CrunchMinute(T1, Queues.EeaDesk, validMinuteMilli, 1, 0, 0, 0, None, None, None, None, None),
          CrunchMinute(T1, Queues.EeaDesk, correctedMillis, 2, 0, 0, 0, None, None, None, None, None)
        ).map(m => (m.key, m))
        val expectedStaffMinutes = SortedMap[TM, StaffMinute]() ++ Seq(
          StaffMinute(T1, validMinuteMilli, 1, 0, 0, None),
          StaffMinute(T1, correctedMillis, 2, 0, 0, None)
        ).map(m => (m.key, m))

        val expected = PortState(SortedMap[UniqueArrival, ApiFlightWithSplits](), expectedCrunchMinutes, expectedStaffMinutes)

        state === expected
      }
    }
  }

  "Given a CrunchMinute" >> {
    "I should be able serialise and deserialise it without loss" >> {
      val cm = CrunchMinute(
        terminal = T1,
        queue = EeaDesk,
        minute = 60000L,
        paxLoad = 1.1,
        workLoad = 25.6,
        deskRec = 2,
        waitTime = 2,
        maybePaxInQueue = Option(45),
        deployedDesks = Option(3),
        deployedWait = Option(4),
        maybeDeployedPaxInQueue = Option(47),
        actDesks = Option(1),
        actWait = Option(10),
        lastUpdated = Option(1200L),
      )
      val serialised = PortStateMessageConversion.crunchMinuteToMessage(cm)

      PortStateMessageConversion.crunchMinuteFromMessage(serialised) === cm
    }
  }
}
