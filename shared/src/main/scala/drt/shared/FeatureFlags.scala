package drt.shared

import upickle.default._

case class FeatureFlags(useApiPaxNos: Boolean,
                        displayWaitTimesToggle: Boolean,
                        displayRedListInfo: Boolean)

object FeatureFlags {
  implicit val rw: ReadWriter[FeatureFlags] = macroRW

//  def apply(config: play.api.Configuration)
//  sealed trait FeatureFlag {
//    val enabled: Boolean
//  }
//
//  case class UseApiPaxNos(enabled: Boolean) extends FeatureFlag
//
//  case class DisplayWaitTimesToggle(enabled: Boolean) extends FeatureFlag
//
//  case class DisplayRedListInfo(enabled: Boolean) extends FeatureFlag
//
//  object DisplayWaitTimesToggle {
//    implicit val rw: ReadWriter[DisplayWaitTimesToggle] = macroRW
//  }
//
//  object DisplayRedListInfo {
//    implicit val rw: ReadWriter[DisplayRedListInfo] = macroRW
//  }
//
//  object UseApiPaxNos {
//    implicit val rw: ReadWriter[UseApiPaxNos] = macroRW

    //    implicit val feedSourceReadWriter: ReadWriter[FeatureFlag] =
    //      readwriter[Value].bimap[FeatureFlag](
    //        feedSource => feedSource.toString,
    //        (s: Value) => apply(s.str).getOrElse(UnknownFeedSource)
    //      )
//  }
}
