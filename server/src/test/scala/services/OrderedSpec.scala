package services

import drt.shared.Terminals.{T1, T2}
import drt.shared._
import org.specs2.mutable.Specification

import scala.collection.SortedSet

class OrderedSpec extends Specification {
  "CodeShareKeyOrderedByDupes" >> {
    "Given two CodeShareKeyOrderedByDupes with identical sch, term & origin, but one with 2 ArrivalKeys and the other with one " +
      "When adding them in order of number of arrival keys and then sorting them " +
      "The head item should be the one with 2 arrival keys" >> {
      val cs1 = CodeShareKeyOrderedByDupes(0L, T1, PortCode("LHR"), Set(ArrivalKey(PortCode("LHR"), VoyageNumber(1), 0L)))
      val cs2 = CodeShareKeyOrderedByDupes(0L, T1, PortCode("LHR"), Set(ArrivalKey(PortCode("LHR"), VoyageNumber(1), 0L), ArrivalKey(PortCode("LHR"), VoyageNumber(1), 0L)))

      val sorted = Seq(cs1, cs2).sorted

      sorted.head === cs2
    }
  }

  "ArrivalKey" >> {
    "Given two ArrivalKey with identical origin, voyage number & scheduled " +
      "When adding them to a Set " +
      "The Set's size should be 1" >> {
      val cs1 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 0L)
      val cs2 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 0L)

      val setSize = Set(cs1, cs2).size

      setSize === 1
    }

    "Given two ArrivalKey with identical origin & voyage number, but scheduleds of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 1L)
      val cs2 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two ArrivalKey with identical origin & scheduled, but voyage numbers 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = ArrivalKey(PortCode("AAA"), VoyageNumber(1), 0L)
      val cs2 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two ArrivalKey with identical voyage numbers & scheduleds, but origins of AAA & BBB " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = ArrivalKey(PortCode("BBB"), VoyageNumber(0), 0L)
      val cs2 = ArrivalKey(PortCode("AAA"), VoyageNumber(0), 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }
  }

  "UniqueArrival" >> {
    "Given two UniqueArrival with identical voyage number, terminal & scheduled " +
      "When adding them to a Set " +
      "The Set's size should be 1" >> {
      val cs1 = UniqueArrival(0, T1, 0L)
      val cs2 = UniqueArrival(0, T1, 0L)

      val setSize = Set(cs1, cs2).size

      setSize === 1
    }

    "Given two UniqueArrival with identical voyage number & terminal, but scheduled of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = UniqueArrival(0, T1, 1L)
      val cs2 = UniqueArrival(0, T1, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two UniqueArrival with identical voyage number & scheduled, but terminals of T1 & T2 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = UniqueArrival(0, T2, 0L)
      val cs2 = UniqueArrival(0, T1, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two UniqueArrival with identical scheduled & terminal, but voyage numbers of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = UniqueArrival(1, T1, 0L)
      val cs2 = UniqueArrival(0, T1, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }
  }

  "CodeShareKeyOrderedBySchedule" >> {
    "Given two CodeShareKeyOrderedBySchedule with identical sch, term & origin " +
      "When adding them to a Set " +
      "The Set's size should be 1" >> {
      val cs1 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("LHR"))
      val cs2 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("LHR"))

      val setSize = Set(cs1, cs2).size

      setSize === 1
    }

    "Given two CodeShareKeyOrderedBySchedule with identical sch & term, but origins of AAA & BBB " +
      "When adding them to a SortedSet " +
      "They should be ordered with AAA first, and BBB last" >> {
      val cs1 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("BBB"))
      val cs2 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("AAA"))

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two CodeShareKeyOrderedBySchedule with identical sch & origins but terminals T1 & T2 " +
      "When adding them to a SortedSet " +
      "They should be ordered with T1 first, and T2 last" >> {
      val cs1 = CodeShareKeyOrderedBySchedule(0L, T2, PortCode("AAA"))
      val cs2 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("AAA"))

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two CodeShareKeyOrderedBySchedule with identical origins & terminals, but schedules of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 0 first, and 1 last" >> {
      val cs1 = CodeShareKeyOrderedBySchedule(1L, T1, PortCode("AAA"))
      val cs2 = CodeShareKeyOrderedBySchedule(0L, T1, PortCode("AAA"))

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }
  }

  "TQM" >> {
    "Given two TQMs with identical terminal, queue & min " +
      "When adding them to a Set " +
      "The Set's size should be 1" >> {
      val cs1 = TQM(T1, Queues.EeaDesk, 0L)
      val cs2 = TQM(T1, Queues.EeaDesk, 0L)

      val setSize = Set(cs1, cs2).size

      setSize === 1
    }

    "Given two TQMs with identical terminal & queue but minutes of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 1 first, and 1 last" >> {
      val cs1 = TQM(T1, Queues.EeaDesk, 1L)
      val cs2 = TQM(T1, Queues.EeaDesk, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two TQMs with identical terminal & minute but queues of Eea & NonEea " +
      "When adding them to a SortedSet " +
      "They should be ordered with Eea first, and NonEea last" >> {
      val cs1 = TQM(T2, Queues.NonEeaDesk, 0L)
      val cs2 = TQM(T1, Queues.EeaDesk, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two TQMs with identical queues & minutes but terminals of T1 & T2 " +
      "When adding them to a SortedSet " +
      "They should be ordered with T1 first, and T2 last" >> {
      val cs1 = TQM(T2, Queues.EeaDesk, 0L)
      val cs2 = TQM(T1, Queues.EeaDesk, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }
  }

  "TM" >> {
    "Given two TMs with identical terminals & minutes " +
      "When adding them to a Set " +
      "The Set's size should be 1" >> {
      val cs1 = TM(T1, 0L)
      val cs2 = TM(T1, 0L)

      val setSize = Set(cs1, cs2).size

      setSize === 1
    }

    "Given two TMs with identical terminals but minutes of 0 & 1 " +
      "When adding them to a SortedSet " +
      "They should be ordered with 1 first, and 1 last" >> {
      val cs1 = TM(T1, 1L)
      val cs2 = TM(T1, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }

    "Given two TMs with identical minutes but terminals of T1 & T2 " +
      "When adding them to a SortedSet " +
      "They should be ordered with T1 first, and T2 last" >> {
      val cs1 = TM(T2, 0L)
      val cs2 = TM(T1, 0L)

      val sorted = SortedSet(cs1, cs2).toSeq

      sorted === Seq(cs2, cs1)
    }
  }
}
