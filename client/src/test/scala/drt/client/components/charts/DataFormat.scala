package drt.client.components.charts

import scala.scalajs.js
import scala.scalajs.js.JSON

object DataFormat {
  def jsonString(jsItem: js.Any) = JSON.stringify(jsItem)
}
