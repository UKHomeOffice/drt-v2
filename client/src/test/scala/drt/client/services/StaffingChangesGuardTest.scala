package drt.client.services

import drt.client.components.MonthlyStaffingComponent.hasChanged
import drt.client.components.{ MonthlyShifts, ShiftDate, ShiftDateTime, ShiftSummary, ShiftSummaryStaffing, StaffTableEntry }
import japgolly.scalajs.react.Callback
import utest._

object StaffingChangesGuardTest extends TestSuite {
  def tests: Tests = Tests {
    test("clean navigation runs without confirmation") {
      val guard = new StaffingChangesGuard
      var actions = Vector.empty[String]
      guard.subscribe(open => actions :+= s"dialog:$open")

      guard.request(Callback { actions :+= "navigate" }).runNow()

      assert(actions == Vector("navigate"))
    }

    test("cancelling keeps edits and the requested action is not run") {
      val guard = new StaffingChangesGuard
      var actions = Vector.empty[String]
      guard.subscribe(open => actions :+= s"dialog:$open")
      guard.markEdited()

      guard.request(Callback { actions :+= "navigate" }).runNow()
      guard.cancel()

      assert(guard.hasChanges)
      assert(actions == Vector("dialog:true", "dialog:false"))
    }

    test("confirming discards edits and runs only the first requested action") {
      val guard = new StaffingChangesGuard
      var actions = Vector.empty[String]
      guard.subscribe(open => actions :+= s"dialog:$open")
      guard.markEdited()

      guard.request(Callback { actions :+= "navigate" }, Callback { actions :+= "first" }).runNow()
      guard.request(Callback { actions :+= "navigate" }, Callback { actions :+= "second" }).runNow()
      guard.confirm()

      assert(!guard.hasChanges)
      assert(actions == Vector("dialog:true", "dialog:false", "first"))
    }

    test("saving clears the warning") {
      val guard = new StaffingChangesGuard
      guard.markEdited()
      guard.markSaved()
      assert(!guard.hasChanges)
    }

    test("leaving the editor clears its edits and any open dialog") {
      val guard = new StaffingChangesGuard
      var isOpen = false
      guard.subscribe(open => isOpen = open)
      guard.markEdited()
      guard.request(Callback.empty).runNow()
      guard.abandon()
      assert(!guard.hasChanges)
      assert(!isOpen)
    }

    test("re-entering a cell without changing its value is not an edit") {
      assert(!hasChanged(1, "1"))
      assert(!hasChanged("0", 0))
      assert(!hasChanged("", ""))
      assert(hasChanged(1, "2"))
      assert(hasChanged(1, ""))
    }

    test("unchanged shift staffing entries are not edits") {
      val time = ShiftDateTime(2026, 1, 1, 0, 0)
      val entry = StaffTableEntry(1, 0, "Shift", 0, 1, 0L, time, time)
      val shift = ShiftSummary("Shift", 1, "00:00", "01:00", ShiftDate(1, 1, 2026), None)
      val previous = MonthlyShifts.staffNumbers(Seq(ShiftSummaryStaffing(0, shift, Seq(entry))))

      assert(!MonthlyShifts.hasEdits(previous, Seq(entry)))
      entry.staffNumber = 2
      assert(MonthlyShifts.hasEdits(previous, Seq(entry)))
    }
  }
}
