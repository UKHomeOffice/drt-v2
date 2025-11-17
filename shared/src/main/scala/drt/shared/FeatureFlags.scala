package drt.shared

import upickle.default._

case class FeatureFlags(useApiPaxNos: Boolean,
                        displayWaitTimesToggle: Boolean,
                        displayRedListInfo: Boolean,
                        enableShiftPlanningChange: Boolean,
                        enableStaffingPageWarnings: Boolean,
                       )

object FeatureFlags {
  implicit val rw: ReadWriter[FeatureFlags] = macroRW
}
