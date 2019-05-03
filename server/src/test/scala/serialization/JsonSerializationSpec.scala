package serialization

import drt.shared.PaxTypes._
import drt.shared._
import org.specs2.mutable.Specification
import services.AirportToCountry
import upickle.default._

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
  }
}
