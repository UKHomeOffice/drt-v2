package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import utest.{TestSuite, _}

object ViewModeTests extends TestSuite {
  val nowMillis: MillisSinceEpoch = SDate.now().millisSinceEpoch

  override def tests: Tests = Tests {
    test("ViewMode") - {
      test("Given a ViewLive") - {
        val viewLive = ViewLive
        test("When I ask if it's historic") - {
          val isHistoric = viewLive.isHistoric(SDate.now())
          test("Then I should get false") - {
            assert(!isHistoric)
          }
        }
      }

      test("Given a ViewDay with a time machine date") - {
        val viewLive = ViewDay(SDate(0L).toLocalDate, Option(SDate(0L)))
        test("When I ask if it's historic") - {
          val isHistoric = viewLive.isHistoric(SDate.now())
          test("Then I should get true") - {
            assert(isHistoric)
          }
        }
      }

      test("Given a ViewDay with a now of 2020-06-01T00:00 BST, and date of the same") - {
        val now = SDate("2020-06-01T00:00")
        val viewDay = ViewDay(now.toLocalDate, None)
        test("When I ask if it's historic") - {
          test("Then I should get false") - {
            assert(!viewDay.isHistoric(now))
          }
        }
      }

      test("Given a ViewDay with a now of 2020-06-01T00:00 BST, and date of midnight that morning") - {
        val now = SDate("2020-06-01T00:00")
        val viewDay = ViewDay(SDate("2020-06-01T00:00").toLocalDate, None)
        test("When I ask if it's historic") - {
          test("Then I should get false") - {
            assert(!viewDay.isHistoric(now))
          }
        }
      }

      test("Given a ViewDay with a now of 2020-06-01T00:00 BST, and date of one minutes before midnight that morning") - {
        val now = SDate("2020-06-01T00:00")
        val viewDay = ViewDay(SDate("2020-06-01T00:00").addMinutes(-1).toLocalDate, None)
        test("When I ask if it's historic") - {
          test("Then I should get false") - {
            assert(viewDay.isHistoric(now))
          }
        }
      }
    }

    test("Given the same two live view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false") - {
      val currentViewMode = ViewLive

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    test("Given the same two day view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false") - {
      val currentViewMode = ViewDay(SDate.now().toLocalDate, None)

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    test("Given the same two time machine view mode instances " +
      "When asking if they're different " +
      "Then the answer should be false") - {
      val currentViewMode = ViewDay(SDate(0L).toLocalDate, Option(SDate(1L)))

      val isDifferent = currentViewMode.isDifferentTo(currentViewMode)
      val expected = false

      assert(isDifferent == expected)
    }

    test("Given two distinct day view mode instances of the same type " +
      "When asking if they're different " +
      "Then the answer should be yes") - {
      val now = SDate.now()
      val currentViewMode = ViewDay(now.toLocalDate, None)
      val newSameViewMode = ViewDay(now.toLocalDate, None)

      val isDifferent = currentViewMode.isDifferentTo(newSameViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    test("Given a live view mode and a day view mode " +
      "When asking if they're different " +
      "Then the answer should be yes") - {
      val liveViewMode = ViewLive
      val dayViewMode = ViewDay(SDate.now().toLocalDate, None)

      val isDifferent = liveViewMode.isDifferentTo(dayViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    test("Given a live view mode and a time machine view mode " +
      "When asking if they're different " +
      "Then the answer should be yes") - {
      val liveViewMode = ViewLive
      val timeMachineViewMode = ViewDay(SDate.now().toLocalDate, Option(SDate.now()))

      val isDifferent = liveViewMode.isDifferentTo(timeMachineViewMode)
      val expected = true

      assert(isDifferent == expected)
    }

    test("Given a day view mode and a time machine view mode " +
      "When asking if they're different " +
      "Then the answer should be yes") - {
      val dayViewMode = ViewDay(SDate.now().toLocalDate, None)
      val timeMachineViewMode = ViewDay(SDate.now().toLocalDate, Option(SDate.now()))

      val isDifferent = dayViewMode.isDifferentTo(timeMachineViewMode)
      val expected = true

      assert(isDifferent == expected)
    }
  }
}
