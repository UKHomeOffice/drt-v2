import drt.shared.TQM
import org.specs2.mutable.Specification
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.Map

class MemorySpec extends Specification {
  "Given a Map of stuff " +
    "When I transform it to a Set " +
    "How much memory allocation is triggered" >> {
    val stuff = Map[TQM, LoadMinute]
  }
}
