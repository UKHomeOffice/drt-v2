package controllers

import controllers.application.TestDrtModule
import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import org.scalatestplus.play.PlaySpec
import uk.gov.homeoffice.drt.ShiftMeta
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.service.staffing.LegacyShiftAssignmentsService
import uk.gov.homeoffice.drt.testsystem.{MockDrtParameters, MockShiftAssignmentsService}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class ShiftMetaInfoMigrationSpec extends PlaySpec {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  def moduleWithShiftPlanningFeatureEnabled(staffAssignments: Seq[StaffAssignmentLike]): TestDrtModule = new TestDrtModule() {
    override lazy val drtParameters: MockDrtParameters = new MockDrtParameters {
      override val isTestEnvironment = true
      override val enableShiftPlanningChange = true
    }
    override val legacyShiftAssignmentsService: LegacyShiftAssignmentsService = MockShiftAssignmentsService(staffAssignments)
    override val shiftAssignmentsService: LegacyShiftAssignmentsService = MockShiftAssignmentsService(Seq())
  }

  def moduleWithShiftPlanningFeatureDisabled(staffAssignments: Seq[StaffAssignmentLike]): TestDrtModule = new TestDrtModule() {
    override lazy val drtParameters: MockDrtParameters = new MockDrtParameters {
      override val isTestEnvironment = true
      override val enableShiftPlanningChange = false
    }
    override val legacyShiftAssignmentsService: LegacyShiftAssignmentsService = MockShiftAssignmentsService(staffAssignments)
    override val shiftAssignmentsService: LegacyShiftAssignmentsService = MockShiftAssignmentsService(Seq())
  }

  "ShiftMetaInfoService" should {
        "insert and retrieve ShiftMeta correctly" in {
          val drtSystemInterface = moduleWithShiftPlanningFeatureEnabled(Seq.empty).provideDrtSystemInterface
          val service = drtSystemInterface.shiftMetaInfoService
          val port = "LHR"
          val terminal: Terminal = uk.gov.homeoffice.drt.ports.Terminals.T1
          val shiftAssignmentsMigratedAt = SDate("2021-07-01").millisSinceEpoch
          val shiftMeta = ShiftMeta(port, terminal.toString, Some(shiftAssignmentsMigratedAt))
          val initial = Await.result(service.getShiftMetaInfo(port, terminal.toString), 5.seconds)
          initial mustBe None
          initial.size mustBe 0

          val insertResult = Await.result(service.insertShiftMetaInfo(shiftMeta), 5.seconds)
          insertResult mustBe 1

          val retrievedResult = Await.result(service.getShiftMetaInfo(port, terminal.toString), 5.seconds)

          retrievedResult mustBe Some(shiftMeta)
        }

    "check migration of shift assignments is done when feature flag is enabled" in {
      val shiftAssignments = Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T10:00").millisSinceEpoch, SDate("2024-07-01T10:15").millisSinceEpoch, 1, None))
      val module = moduleWithShiftPlanningFeatureEnabled(shiftAssignments)
      val drtSystemInterface = module.provideDrtSystemInterface
      val controller = new ShiftMetaInfoMigrationController(drtSystemInterface,
        module.legacyShiftAssignmentsService,
        module.shiftAssignmentsService)(ec)
      val service = drtSystemInterface.shiftMetaInfoService
      val port = "TEST"
      val terminal: Terminal = uk.gov.homeoffice.drt.ports.Terminals.T1
      val shiftAssignmentsBefore : Future[ShiftAssignments] = module.shiftAssignmentsService.allShiftAssignments
      Await.result(shiftAssignmentsBefore, 5.seconds).assignments mustBe Seq()
      val shiftAssignmentsMigratedAt = SDate("2024-07-01").millisSinceEpoch
      Await.result(controller.checkAndMarkShiftAssignmentsMigration(shiftAssignmentsMigratedAt, service), 5.seconds)
      val retrievedResult = Await.result(service.getShiftMetaInfo(port, terminal.toString), 5.seconds)
      retrievedResult.size mustBe 1
      val shiftAssignmentsAfterMigration : Future[ShiftAssignments] = module.shiftAssignmentsService.allShiftAssignments
      Await.result(shiftAssignmentsAfterMigration, 5.seconds).assignments mustBe shiftAssignments
    }

    "check migration of shift assignments is not done when feature flag is disabled" in {
      val shiftAssignments = Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T10:00").millisSinceEpoch, SDate("2024-07-01T10:15").millisSinceEpoch, 1, None))
      val moduleWithDisabledShiftPlanning = moduleWithShiftPlanningFeatureDisabled(shiftAssignments)
      val drtSystemInterface = moduleWithDisabledShiftPlanning.provideDrtSystemInterface
      val controller = new ShiftMetaInfoMigrationController(drtSystemInterface,
        moduleWithDisabledShiftPlanning.legacyShiftAssignmentsService,
        moduleWithDisabledShiftPlanning.shiftAssignmentsService)(ec)
      val service = drtSystemInterface.shiftMetaInfoService
      val port = "TEST"
      val terminal: Terminal = uk.gov.homeoffice.drt.ports.Terminals.T1
      val shiftAssignmentsBefore : Future[ShiftAssignments] = moduleWithDisabledShiftPlanning.shiftAssignmentsService.allShiftAssignments
      Await.result(shiftAssignmentsBefore, 5.seconds).assignments mustBe Seq()

      val shiftAssignmentsMigratedAt = SDate("2024-07-01").millisSinceEpoch
      Await.result(controller.checkAndMarkShiftAssignmentsMigration(shiftAssignmentsMigratedAt, service), 5.seconds)
      val retrievedResult = Await.result(service.getShiftMetaInfo(port, terminal.toString), 5.seconds)
      retrievedResult.size mustBe 0

      val shiftAssignmentsAfterMigration : Future[ShiftAssignments] = moduleWithDisabledShiftPlanning.shiftAssignmentsService.allShiftAssignments
      Await.result(shiftAssignmentsAfterMigration, 5.seconds).assignments mustBe Seq()
    }
  }
}