package services.graphstages

import drt.shared.{Arrival, PortCode}
import drt.shared.Terminals.T1
import org.specs2.mutable.Specification
import services.SDate

class LiveArrivalsUtilSpec extends Specification {

  def arrival(
               estimated: Option[Long] = None,
               actual: Option[Long] = None,
               estChox: Option[Long] = None,
               actChox: Option[Long] = None,
               gate: Option[String] = None,
               status: String = "test"
             ): Arrival =
    Arrival(
      None,
      status,
      estimated,
      actual,
      estChox,
      actChox,
      gate,
      None,
      None,
      None,
      None,
      None,
      None,
      PortCode("STN"),
      T1,
      "TST100",
      "TST100",
      PortCode("TST"),
      SDate(2019,
        9,
        30,
        16, 0).millisSinceEpoch,
      None,
      Set()
    )

  "Given a BaseLiveArrival with all landing times set and port arrival with none then I should get the BaseArrival times" >> {
    val baseArrival = arrival(Option(SDate(2019, 9, 30, 16, 1).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 2).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 3).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 4).millisSinceEpoch), None, "Test")
    val liveArrival = arrival(gate = None)

    val expected = liveArrival.copy(
      Estimated = baseArrival.Estimated,
      EstimatedChox = baseArrival.EstimatedChox,
      Actual = baseArrival.Actual,
      ActualChox = baseArrival.ActualChox
    )

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with no gate and a port arrival with a gate then I should use the gate from port arrival" >> {
    val baseArrival = arrival(status = "Test")
    val liveArrival = arrival(gate = Option("Gate"))
    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with all landing times set and port arrival all times set then I should get the port times" >> {
    val baseArrival = arrival(Option(SDate(2019, 9, 30, 16, 1).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 2).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 3).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 4).millisSinceEpoch))

    val liveArrival = arrival(Option(SDate(2019, 9, 30, 16, 5).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 6).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 7).millisSinceEpoch), Option(SDate(2019, 9, 30, 16, 8).millisSinceEpoch))
    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a gate and a port arrival with no gate then I should use the gate from base arrival" >> {
    val baseArrival = arrival(gate = Option("Gate"))
    val liveArrival = arrival(status = "Test")

    val expected = liveArrival.copy(Gate = baseArrival.Gate)

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a gate and a port arrival with a gate then I should use the gate from port arrival" >> {
    val baseArrival = arrival(gate = Option("Base Gate"))
    val liveArrival = arrival(gate = Option("Port Gate"))

    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a status and a port arrival with a status of UNK then I should use the base status" >> {
    val baseArrival = arrival(status = "Landed")
    val liveArrival = arrival(status = "UNK")

    val expected = liveArrival.copy(Status = baseArrival.Status)

    val result = LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseArrival)

    result === expected
  }

}
