package manifests.graph

import controllers.ArrivalGenerator
import drt.shared.ArrivalKey
import org.specs2.mutable.SpecificationLike

import scala.collection.mutable

class BatchStageSpec extends SpecificationLike {
  "Given an empty arrivals registry" >> {
    def emptyRegistry: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap[ArrivalKey, Option[Long]]()

    "A single new arrival should return a List of one arrival" >> {
      val registry = emptyRegistry
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", origin = "JFK", schDt = "2019-01-01T00:00")
      val result = BatchStage.arrivalsToRegister(List(arrival), registry)

      result === List((ArrivalKey(arrival), None))
    }
  }

  "Given an empty arrivals & arrival updates registry" >> {
    def emptyRegistry: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap[ArrivalKey, Option[Long]]()

    "A single new arrival should return a List of one arrival" >> {
      val registry = emptyRegistry
      val updates = emptyRegistry
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", origin = "JFK", schDt = "2019-01-01T00:00")
      BatchStage.registerNewArrivals(List(arrival), registry, updates)

      val expected = mutable.SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), None))

      registry === expected && updates === expected
    }
  }
}
