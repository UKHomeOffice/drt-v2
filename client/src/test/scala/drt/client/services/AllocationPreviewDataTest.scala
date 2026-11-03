package drt.client.services

import drt.client.services.AllocationPreviewState.Context
import drt.client.services.JSDateConversions.SDate
import drt.shared.AllocationPreview.{ Response, Result }
import drt.shared.CrunchApi.StaffMinute
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Queues.NonEeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.LocalDate
import utest._

object AllocationPreviewDataTest extends TestSuite {
  val day = SDate("2026-10-30T00:00Z")
  val recommended: Result = Result(
    Seq(EeaDesk),
    (0 until 60).map(i => CrunchMinute(T1, EeaDesk, day.addMinutes(i).millisSinceEpoch, 2, 4, 3, i, Some(i))),
    (0 until 60).map(i => StaffMinute(T1, day.addMinutes(i).millisSinceEpoch, 8, 1, 2, None)),
    Map(EeaDesk -> 15)
  )
  val available: Result = recommended.copy(
    minutes = recommended.minutes.map(_.copy(
      deskRec = 999,
      waitTime = 999,
      maybePaxInQueue = Some(999),
      deployedDesks = Some(2),
      deployedWait = Some(7),
      maybeDeployedPaxInQueue = Some(17)
    )),
    slas = Map(EeaDesk -> 20)
  )
  val response: Response = Response(
    day.millisSinceEpoch,
    day.addDays(1).millisSinceEpoch,
    Some(recommended),
    Some(available)
  )
  val state: AllocationPreviewState = AllocationPreviewState(
    active = true,
    context = Some(Context(T1, day.toLocalDate, eligible = true)),
    response = Some(response)
  )

  private def capturedState(date: LocalDate, offsetMinutes: Int, clipEndMinutes: Int = 0): AllocationPreviewState = {
    val dayStart = SDate(date)
    val start = dayStart.addMinutes(-offsetMinutes)
    val end = dayStart.addDays(1).addMinutes(-offsetMinutes - clipEndMinutes)
    val minuteCount = ((end.millisSinceEpoch - start.millisSinceEpoch) / 60000L).toInt
    val captured = recommended.copy(
      minutes = (0 until minuteCount).map(i =>
        CrunchMinute(T1, EeaDesk, start.addMinutes(i).millisSinceEpoch, 1, 2, 3, 16, Some(12))
      ),
      staffMinutes = (0 until minuteCount).map(i =>
        StaffMinute(T1, start.addMinutes(i).millisSinceEpoch, 8, 1, 2, None)
      )
    )
    state.copy(
      context = Some(Context(T1, date, eligible = true)),
      response = Some(response.copy(
        startMillis = start.millisSinceEpoch,
        endMillis = end.millisSinceEpoch,
        recommended = Some(captured),
        available = Some(captured)
      ))
    )
  }

  val tests: Tests = Tests {
    test("same frozen minute data powers window table and full day chart at 15 minutes and hourly") {
      Seq(15, 60).foreach { interval =>
        val data = AllocationPreviewData.aggregate(state, T1, day, 1, interval).get
        assert(data.windowCrunch == data.dayCrunch.take(60 / interval))
        assert(data.windowCrunch.values.map(_(EeaDesk).paxLoad).sum == 120)
        assert(data.staff.values.forall(_.fixedPoints == 1))
        assert(data.staff.values.forall(_.movements == 2))
        assert(data.windowCrunch.values.map(_(EeaDesk).waitTime).max == 59)
        assert(data.recommendedStaff.values.toSet == Set(4), data.availableStaff.values.toSet == Set(Some(3)))
      }
      assert(state.response.contains(response))
    }
    test(
      "retained companion fields preserve each kind's modeled queue size and SLA"
    ) {
      val frozen = AllocationPreviewData.frozenPortState(response, available = false).get.crunchMinutes.values.head
      assert(frozen.deskRec == 3, frozen.deployedDesks.contains(2))
      assert(AllocationPreviewData.queueSize(frozen, "recommended").contains(0))
      assert(AllocationPreviewData.queueSize(frozen, "available").contains(17))
      val availableData = AllocationPreviewData.aggregate(state, T1, day, 1, 15, available = true).get
      assert(availableData.recommendedStaff.values.toSet == Set(4))
      assert(availableData.availableStaff.values.toSet == Set(Some(3)))
      assert(AllocationPreviewData.selectedResult(response, available = true).get.slas(EeaDesk) == 20)
      assert(AllocationPreviewData.selectedResult(response, available = false).get.slas(EeaDesk) == 15)
    }
    test("only selected-kind results produce aggregates; uncalculated companion totals are absent, not zero") {
      val recOnly = state.copy(response = Some(response.copy(available = None)))
      val recData = AllocationPreviewData.aggregate(recOnly, T1, day, 1, 15).get
      assert(recData.recommendedStaff.values.toSet == Set(4), recData.availableStaff.isEmpty)
      assert(AllocationPreviewData.aggregate(recOnly, T1, day, 1, 15, available = true).isEmpty)
      val availableOnly = state.copy(response = Some(response.copy(recommended = None)))
      val availableData = AllocationPreviewData.aggregate(availableOnly, T1, day, 1, 15, available = true).get
      assert(availableData.recommendedStaff.isEmpty, availableData.availableStaff.values.toSet == Set(Some(3)))
      assert(availableData.windowCrunch.keySet == recData.windowCrunch.keySet)
    }
    test("independent retained calculations keep their own staffing inputs in companion totals") {
      val changed = available.copy(staffMinutes = available.staffMinutes.map(_.copy(fixedPoints = 10)))
      val independent = state.copy(response = Some(response.copy(available = Some(changed))))
      val recData = AllocationPreviewData.aggregate(independent, T1, day, 1, 15).get
      val availableData = AllocationPreviewData.aggregate(independent, T1, day, 1, 15, available = true).get
      assert(recData.recommendedStaff.values.toSet == Set(4), recData.availableStaff.values.toSet == Set(Some(12)))
      assert(availableData.recommendedStaff == recData.recommendedStaff)
      assert(availableData.availableStaff == recData.availableStaff)
    }
    test("cached selected results remain usable while another kind is pending; absent results never fall back") {
      val pendingOther = state.copy(pendingKind = Some("available"))
      assert(AllocationPreviewData.aggregate(pendingOther, T1, day, 1, 15) ==
        AllocationPreviewData.aggregate(state, T1, day, 1, 15))
      val absent = pendingOther.copy(response = Some(response.copy(recommended = None)))
      assert(AllocationPreviewData.aggregate(absent, T1, day, 1, 15).isEmpty)
      assert(AllocationPreviewData.aggregate(state.copy(response = None), T1, day, 1, 15).isEmpty)
      assert(AllocationPreviewData.aggregate(state.copy(active = false), T1, day, 1, 15).isEmpty)
    }
    test("full frozen local day uses DST day bounds rather than a fixed 24 hour chart") {
      Seq(LocalDate(2026, 10, 25) -> 25, LocalDate(2026, 3, 29) -> 23).foreach { case (date, hours) =>
        Seq(15, 60).foreach { interval =>
          val data = AllocationPreviewData.aggregate(capturedState(date, 0), T1, SDate(date), 24, interval).get
          assert(data.windowCrunch.size == hours * 60 / interval)
          assert(data.windowCrunch == data.dayCrunch)
          assert(data.windowCrunch.values.map(_(EeaDesk).paxLoad).sum == hours * 60)
          assert(data.staff.size == data.windowCrunch.size)
          assert(data.recommendedStaff.keySet == data.windowCrunch.keySet)
        }
      }
    }
    test("nonzero crunch offset and clipped capture end intersect captured request and selected day") {
      val cases = for {
        date <- Seq(LocalDate(2026, 10, 30), LocalDate(2026, 3, 29), LocalDate(2026, 10, 25))
        (offset, clip) <- Seq(240 -> 0, -240 -> 0, 30 -> 0, -30 -> 0, 240 -> 7)
      } yield (date, offset, clip)
      cases.foreach { case (date, offset, clip) =>
        val captured = capturedState(date, offset, clip)
        val capture = captured.response.get
        val dayStart = SDate(date)
        Seq(15, 60).foreach { interval =>
          val data = AllocationPreviewData.aggregate(captured, T1, dayStart, 24, interval).get
          val expected = (dayStart.millisSinceEpoch until dayStart.addDays(1).millisSinceEpoch by interval * 60000L)
            .filter(t => t >= capture.startMillis && t + interval * 60000L <= capture.endMillis)
          assert(data.windowCrunch == data.dayCrunch)
          assert(data.windowCrunch.keys.toSeq == expected)
          assert(data.windowCrunch.values.map(_(EeaDesk).paxLoad).sum == expected.size * interval)
          assert(data.staff.keySet == data.windowCrunch.keySet)
          assert(data.recommendedStaff.keySet == data.windowCrunch.keySet)
        }
      }
    }
    test("missing queue or staff minute omits the whole absolute-grid slot, not a plausible zero") {
      val minutes = recommended.minutes ++ recommended.minutes.map(_.copy(queue = NonEeaDesk))
      val incomplete = recommended.copy(
        queues = Seq(EeaDesk, NonEeaDesk),
        minutes = minutes.filterNot(m => m.queue == NonEeaDesk && m.minute == day.addMinutes(20).millisSinceEpoch),
        staffMinutes = recommended.staffMinutes.filterNot(_.minute == day.addMinutes(40).millisSinceEpoch),
        slas = Map(EeaDesk -> 15, NonEeaDesk -> 45)
      )
      val missing = state.copy(response =
        Some(response.copy(recommended = Some(incomplete), available = Some(incomplete)))
      )
      val quarterly = AllocationPreviewData.aggregate(missing, T1, day, 1, 15).get
      assert(quarterly.windowCrunch.keys.toSeq == Seq(day.millisSinceEpoch, day.addMinutes(45).millisSinceEpoch))
      assert(quarterly.windowCrunch == quarterly.dayCrunch)
      assert(quarterly.recommendedStaff.keySet == quarterly.windowCrunch.keySet)
      assert(quarterly.staff.keySet == quarterly.windowCrunch.keySet)
      assert(AllocationPreviewData.aggregate(missing, T1, day, 1, 60).get.windowCrunch.isEmpty)
    }
  }
}
