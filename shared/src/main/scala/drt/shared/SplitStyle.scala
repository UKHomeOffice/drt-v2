//package drt.shared
//
//import ujson.Value.Value
//import upickle.default.{readwriter, _}
//
//sealed trait SplitStyle {
//  def name: String = getClass.getSimpleName
//}
//
//object SplitStyle {
//  def apply(splitStyle: String): SplitStyle = splitStyle match {
//    case "PaxNumbers$" => PaxNumbers
//    case "PaxNumbers" => PaxNumbers
//    case "Percentage$" => Percentage
//    case "Percentage" => Percentage
//    case "Ratio" => Ratio
//    case _ => UndefinedSplitStyle
//  }
//
//  implicit val splitStyleReadWriter: ReadWriter[SplitStyle] =
//    readwriter[Value].bimap[SplitStyle](
//      feedSource => feedSource.toString,
//      (s: Value) => apply(s.str)
//    )
//}
//
//case object PaxNumbers extends SplitStyle
//
//case object Percentage extends SplitStyle
//
//case object Ratio extends SplitStyle
//
//case object UndefinedSplitStyle extends SplitStyle
