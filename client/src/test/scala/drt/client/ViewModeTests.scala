package drt.client

import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ViewDay, ViewLive, ViewPointInTime}
import utest._

class ViewModeTests extends TestSuite {
  override def tests: Tests = Tests {
    "Given the same two live view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false" - {
      val currentViewMode = ViewLive

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    "Given the same two day view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false" - {
      val currentViewMode = ViewDay(SDate.now())

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    "Given the same two snapshot view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false" - {
      val currentViewMode = ViewPointInTime(SDate.now())

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    "Given two distinct day view mode instances of the same type " +
      "When asking if they're different " +
      "Then the answer should be yes" - {
      val now = SDate.now()
      val currentViewMode = ViewDay(now)
      val newSameViewMode = ViewDay(now)

      val isDifferent = currentViewMode.isDifferentTo(newSameViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    "Given a live view mode and a day view mode " +
      "When asking if they're different " +
      "Then the answer should be yes" - {
      val liveViewMode = ViewLive
      val dayViewMode = ViewDay(SDate.now())

      val isDifferent = liveViewMode.isDifferentTo(dayViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    "Given a live view mode and a snapshot view mode " +
      "When asking if they're different " +
      "Then the answer should be yes" - {
      val liveViewMode = ViewLive
      val snapshotViewMode = ViewPointInTime(SDate.now())

      val isDifferent = liveViewMode.isDifferentTo(snapshotViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    "Given a day view mode and a snapshot view mode " +
      "When asking if they're different " +
      "Then the answer should be yes" - {
      val dayViewMode = ViewDay(SDate.now())
      val snapshotViewMode = ViewPointInTime(SDate.now())

      val isDifferent = dayViewMode.isDifferentTo(snapshotViewMode)
      val expected = true

      assert(isDifferent == expected)
    }
  }
}
