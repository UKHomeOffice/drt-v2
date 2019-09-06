package serialization

import drt.shared.CrunchApi._
import drt.shared.PaxTypes._
import drt.shared._
import org.specs2.mutable.Specification
import services.AirportToCountry
import upickle.default._

import scala.collection.immutable.SortedMap

class JsonSerializationSpec extends Specification {

  "Scala case classes can be serialized to JSON and then deserialized back to case classes without data loss" >> {

    "AirportConfig" >> {

      val lhrAirportConfig = AirportConfigs.confByPort("LHR")

      val lhrAirportConfigAsJson: String = write(lhrAirportConfig)

      val lhrAirportConfigDeserialized: AirportConfig = read[AirportConfig](lhrAirportConfigAsJson)

      lhrAirportConfig === lhrAirportConfigDeserialized
    }

    "PaxTypes" >> {
      import drt.shared.PaxType.paxTypeReaderWriter

      val allPaxTypes = Seq(
        EeaNonMachineReadable,
        Transit,
        VisaNational,
        EeaMachineReadable,
        EeaBelowEGateAge,
        NonVisaNational,
        B5JPlusNational,
        B5JPlusNationalBelowEGateAge,
        UndefinedPaxType
      )

      val asJson: Seq[String] = allPaxTypes.map((pt: PaxType) => write(pt))

      val deserialized: Seq[PaxType] = asJson.map(s => read[PaxType](s))

      deserialized === allPaxTypes
    }

    "Roles" >> {
      val allRoles = Roles.availableRoles

      val asJson: Set[String] = allRoles.map(r => write(r))

      val deserialized: Set[Role] = asJson.map(s => read[Role](s))

      deserialized === allRoles
    }

    "AirportInfo" >> {
      val info: Map[String, AirportInfo] = AirportToCountry.airportInfo

      val asJson: String = write(info)

      val deserialized = read[Map[String, AirportInfo]](asJson)

      deserialized === info
    }

    "PortState" >> {
      val flightWithSplits = ApiFlightWithSplits(
        Arrival(None, "scheduled", None, None, None, None, None, None, None, None, None, None, None, None, "test", "test", "test", "test", "test", 0L, None, Set(AclFeedSource, LiveFeedSource), None),
        Set(
          Splits(
            Set(
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, None),
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, None)
            ), "source", None, Percentage))
      )
      val flightsWithSplits = SortedMap(flightWithSplits.apiFlight.unique -> flightWithSplits)

      val crunchMinutes = SortedMap[TQM, CrunchMinute]() ++ List(
        CrunchMinute("T1", Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0)),
        CrunchMinute("T1", Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0))
      ).map(cm => (TQM(cm), cm))

      val staffMinutes = SortedMap[TM, StaffMinute]() ++ List(
        StaffMinute("T1", 0L, 1, 1, 1, None),
        StaffMinute("T1", 0L, 1, 1, 1, None)
      ).map(sm => (TM(sm), sm))

      val cs = PortState(flightsWithSplits, crunchMinutes, staffMinutes)

      val asJson: String = write(cs)

      val deserialized = read[PortState](asJson)

      deserialized === cs
    }

    "PortStateError" >> {
      val ce = PortStateError("Error Message")

      val json = write(ce)

      val deserialized = read[PortStateError](json)

      deserialized === ce
    }

    "PortStateUpdates" >> {
      val cu = PortStateUpdates(
        0L,
        Set(
          ApiFlightWithSplits(
            Arrival(None, "scheduled", None, None, None, None, None, None, None, None, None, None, None, None, "test", "test", "test", "test", "test", 0L, None, Set(AclFeedSource, LiveFeedSource), None),
            Set(Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, Option(Map("tw" -> 7.0)))), "source", None, Percentage))
          )
        ),
        Set(CrunchMinute("T1", Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0))),
        Set(StaffMinute("T1", 0L, 1, 1,1,None))
      )

      val asJson: String = write(cu)

      val deserialized = read[PortStateUpdates](asJson)

      deserialized === cu
    }

    "FeedStatuses" >> {
      val fss = FeedStatuses(
        "test",
        List(
          FeedStatusFailure(0L, "failure"),
          FeedStatusSuccess(0L, 3)
        ),
        None,
        None,
        None
      )

      val json = write(fss)

      val deserialized = read[FeedStatuses](json)

      deserialized === fss
    }

    "FixedPointAssignments" >> {
      val fpa = FixedPointAssignments(
        Seq(StaffAssignment("test", "test", MilliDate(0L), MilliDate(0L), 0, None))
      )

      val json = write(fpa)

      val deserialized = read[FixedPointAssignments](json)

      deserialized === fpa
    }
  }
}
