package services

import drt.shared.Terminals.{T1, T2, T3}
import drt.shared.{Queues, TQM}
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification

import scala.collection.immutable

class TQMSpec extends Specification {
  "TQM equality" >> {
    "When I add 2 identical TQMs to a set " +
      "I should see a size of 1, and the head should be equal to one of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 0)
      val tqms = Set(tqm1, tqm2)

      tqms.size === 1 && tqms.head === tqm1
    }

    "When I add 2 TQMs to a set " +
      "where only the terminal differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T2, Queues.EeaDesk, 0)
      val tqms = Set(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a set " +
      "where only the queue differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.NonEeaDesk, 0)
      val tqms = Set(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a set " +
      "where only the minute differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 1)
      val tqms = Set(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }
  }

  "TQM comparisons" >> {
    "When I add 2 identical TQMs to a SortedSet " +
      "I should see a size of 1, and the head should be equal to one of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 0)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 1 && tqms.head === tqm1
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the terminal differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T2, Queues.EeaDesk, 0)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the queue differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.NonEeaDesk, 0)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the minute differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 1)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the minute differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 1)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the minute differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T1, Queues.EeaDesk, 1)
      val tqms = immutable.SortedSet(tqm1, tqm2)

      tqms.size === 2 && tqms.contains(tqm1) && tqms.contains(tqm2)
    }

    "When I add 2 TQMs to a SortedSet " +
      "where only the minute differs, " +
      "I should see a size of 2, and the Set should contain both of the TQMs added" >> {
      val tqm1 = TQM(T1, Queues.EeaDesk, 0)
      val tqm2 = TQM(T2, Queues.EeaDesk, 0)
      val tqm3 = TQM(T3, Queues.EeaDesk, 0)
      val tqm4 = TQM(T1, Queues.NonEeaDesk, 10)
      val tqm5 = TQM(T2, Queues.NonEeaDesk, 10)
      val tqm6 = TQM(T3, Queues.NonEeaDesk, 10)
      val tqms = Seq(tqm6, tqm1, tqm5, tqm2, tqm4, tqm3)

      val tqmsInOrder = tqms.sorted
      val expected = Seq(tqm1, tqm2, tqm3, tqm4, tqm5, tqm6)

      tqmsInOrder === expected
    }
  }
}
