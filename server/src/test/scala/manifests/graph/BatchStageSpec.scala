package manifests.graph

import controllers.ArrivalGenerator
import drt.shared.{ArrivalKey, PortCode}
import org.specs2.mutable.SpecificationLike

import scala.collection.immutable.SortedMap

class BatchStageSpec extends SpecificationLike {
  "Given an empty arrivals registry" >> {
    def emptyRegistry: SortedMap[ArrivalKey, Option[Long]] = SortedMap[ArrivalKey, Option[Long]]()

    "A single new arrival should return a List of one arrival" >> {
      val registry = emptyRegistry
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = PortCode("JFK"))
      val result = BatchStage.arrivalsToRegister(List(arrival), registry)

      result === List((ArrivalKey(arrival), None))
    }
  }

  "Given an empty arrivals & arrival updates registry" >> {
    def emptyRegistry: SortedMap[ArrivalKey, Option[Long]] = SortedMap[ArrivalKey, Option[Long]]()

    "A call to registerNewArrivals with one arrival should result in it being added to both registries" >> {
      val registry = emptyRegistry
      val updates = emptyRegistry
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = PortCode("JFK"))
      val (updatedRegistry, updatedUpdates) = BatchStage.registerNewArrivals(List(arrival), registry, updates)

      val expected = SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), None))

      updatedRegistry === expected && updatedUpdates === expected
    }
  }

  "Given arrivals & arrival updates registries both containing a single arrival" >> {
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T00:00", origin = PortCode("JFK"))
    def singleArrivalRegistry: SortedMap[ArrivalKey, Option[Long]] = SortedMap[ArrivalKey, Option[Long]](ArrivalKey(arrival) -> Option(1L))

    "A call to registerNewArrivals with no arrivals should leave both registries the same" >> {
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      val (updatedRegistry, updatedUpdates) = BatchStage.registerNewArrivals(List(), registry, updates)

      val expected = SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), Option(1L)))

      updatedRegistry === expected && updatedUpdates === expected
    }

    "A call to registerNewArrivals with the same arrival should leave both registries the same" >> {
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      val (updatedRegistry, updatedUpdates) = BatchStage.registerNewArrivals(List(arrival), registry, updates)

      val expected = SortedMap[ArrivalKey, Option[Long]]() ++ List((ArrivalKey(arrival), Option(1L)))

      updatedRegistry === expected && updatedUpdates === expected
    }

    "A call to registerNewArrivals with a different arrival should add it to both registries" >> {
      val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2019-01-02T00:00", origin = PortCode("AAA"))
      val registry = singleArrivalRegistry
      val updates = singleArrivalRegistry
      val (updatedRegistry, updatedUpdates) = BatchStage.registerNewArrivals(List(arrival2), registry, updates)

      val expected = SortedMap[ArrivalKey, Option[Long]]() ++ List(
        (ArrivalKey(arrival), Option(1L)),
        (ArrivalKey(arrival2), None)
      )

      updatedRegistry === expected && updatedUpdates === expected
    }
  }
}
