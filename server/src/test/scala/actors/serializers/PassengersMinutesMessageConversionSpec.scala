package actors.serializers

import drt.shared.CrunchApi.PassengersMinute
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1

class PassengersMinutesMessageConversionSpec extends Specification {
  "I should be able to serialise and deserialise some PassengersMinutes without data loss" >> {
    val px = Seq(
      PassengersMinute(T1, EeaDesk, 0L, Seq(1, 2, 3), Option(1L)),
      PassengersMinute(T1, EGate, 2L, Seq(4, 5, 6), Option(3L)),
    )
    val serialised = PassengersMinutesMessageConversion.passengerMinutesToMessage(px)
    val deserialised = serialised.minutes.map(m => PassengersMinutesMessageConversion.passengersMinuteFromMessage(T1, m))

    deserialised === px
  }
}
