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
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = "JFK")
      val result = BatchStage.arrivalsToRegister(List(arrival), registry)

      result === List((ArrivalKey(arrival), None))
    }
  }

  "Given an empty arrivals & arrival updates registry" >> {
    def emptyRegistry: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap[ArrivalKey, Option[Long]]()

    "A call to registerNewArrivals with one arrival should result in it being added to both registries" >> {
      val registry = emptyRegistry
      val updates = emptyRegistry
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = "JFK")
      BatchStage.registerNewArrivals(List(arrival), registry, updates)

      val expected = mutable.SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), None))

      registry === expected && updates === expected
    }
  }

  "Given arrivals & arrival updates registries both containing a single arrival" >> {
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = "JFK")
    def singleArrivalRegistry: mutable.SortedMap[ArrivalKey, Option[Long]] = mutable.SortedMap[ArrivalKey, Option[Long]](ArrivalKey(arrival) -> Option(1L))

    "A call to registerNewArrivals with no arrivals should leave both registries the same" >> {
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      BatchStage.registerNewArrivals(List(), registry, updates)

      val expected = mutable.SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), Option(1L)))

      registry === expected && updates === expected
    }

    "A call to registerNewArrivals with the same arrival should leave both registries the same" >> {
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      BatchStage.registerNewArrivals(List(arrival), registry, updates)

      val expected = mutable.SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), Option(1L)))

      registry === expected && updates === expected
    }

    "A call to registerNewArrivals with a different arrival should add it to both registries" >> {
      val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2019-01-02T00:00", origin = "AAA")
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      BatchStage.registerNewArrivals(List(arrival2), registry, updates)

      val expected = mutable.SortedMap[ArrivalKey, Option[Long]]() ++ List(
        (ArrivalKey(arrival), Option(1L)),
        (ArrivalKey(arrival2), None)
      )

      registry === expected && updates === expected
    }
  }
}
