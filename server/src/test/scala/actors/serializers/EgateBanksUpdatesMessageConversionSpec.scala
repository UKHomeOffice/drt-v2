package actors.serializers

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.egates.{DeleteEgateBanksUpdates, EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}

class EgateBanksUpdatesMessageConversionSpec extends Specification {
  "Given a SetEgateBanksUpdate" >> {
    "I should be able to serialise it and deserialise it without any data loss" >> {
      val setEgateBanksUpdate = SetEgateBanksUpdate(T1, 100L, EgateBanksUpdate(150L, IndexedSeq(EgateBank(IndexedSeq(true, false, true, false)))))
      val serialised = EgateBanksUpdatesMessageConversion.setEgateBanksUpdatesToMessage(setEgateBanksUpdate)
      val deserialised = EgateBanksUpdatesMessageConversion.setEgateBanksUpdateFromMessage(serialised)

      deserialised === Option(setEgateBanksUpdate)
    }
  }

  "Given a SetEgateBanksUpdate" >> {
    "I should be able to serialise it and deserialise it without any data loss" >> {
      val removeEgateBanksUpdate = DeleteEgateBanksUpdates(T1, 150L)
      val serialised = EgateBanksUpdatesMessageConversion.removeEgateBanksUpdateToMessage(removeEgateBanksUpdate)
      val deserialised = EgateBanksUpdatesMessageConversion.removeEgateBanksUpdateFromMessage(serialised)

      deserialised === Option(removeEgateBanksUpdate)
    }
  }

  "Given a PortEgateBanksUpdates" >> {
    "I should be able to serialise it and deserialise it without any data loss" >> {
      val portEgateBanksUpdates = PortEgateBanksUpdates(Map(
        T1 -> EgateBanksUpdates(List(EgateBanksUpdate(100L, IndexedSeq(EgateBank(IndexedSeq(true, false, true)), EgateBank(IndexedSeq(false, true, false)))))),
        T2 -> EgateBanksUpdates(List(EgateBanksUpdate(150L, IndexedSeq(EgateBank(IndexedSeq(false, false, true)), EgateBank(IndexedSeq(true, true, false)))))),
      ))

      val serialised = EgateBanksUpdatesMessageConversion.portUpdatesToMessage(portEgateBanksUpdates)
      val deserialised = EgateBanksUpdatesMessageConversion.portUpdatesFromMessage(serialised)

      deserialised === portEgateBanksUpdates
    }
  }
}
