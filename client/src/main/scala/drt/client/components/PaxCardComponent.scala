package drt.client.components
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.Date
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.scalajs.js.annotation.JSImport

@js.native
trait PortQueue extends js.Object {
  var queueName: String
  var queueCount: Int
}

object PortQueue {
  def apply(queueName: String, queueCount: Int): PortQueue = {
    val p = (new js.Object).asInstanceOf[PortQueue]
    p.queueName = queueName
    p.queueCount = queueCount
    p
  }
}

@js.native
trait IPaxCard extends js.Object {
  var queues: js.Array[PortQueue]
  var timeRange: String
  var startTime: Date
  var endTime: Date
}

object IPaxCard {
  def apply(queues: Seq[PortQueue], timeRange: String, startTime : Date, endTime: Date): IPaxCard = {
    val p = (new js.Object).asInstanceOf[IPaxCard]
    p.queues = queues.toJSArray
    p.timeRange = timeRange
    p.startTime = startTime
    p.endTime = endTime
    p
  }
}

object PaxCardComponent {
  @js.native
  @JSImport("@drt/drt-react", "PaxCard")
  object RawComponent extends js.Object

  val component = JsFnComponent[IPaxCard, Children.None](RawComponent)

  def apply(props: IPaxCard): VdomElement = {
    component(props)
  }

}
