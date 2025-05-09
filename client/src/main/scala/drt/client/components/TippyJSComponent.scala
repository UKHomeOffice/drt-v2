package drt.client.components

import diode.UseValueEq
import drt.client.components.styles.DefaultToolTipsStyle
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Info
import japgolly.scalajs.react.Ref.Simple
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, Children, JsComponent, ReactEventFromInput, Ref, ScalaComponent}
import org.scalajs.dom.raw.{HTMLElement, KeyboardEvent}
import org.scalajs.dom.{Event, document}
import scalacss.ScalaCssReactImplicits

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object TippyJSComponent {

  val log: Logger = LoggerFactory.getLogger("TippyJSComponent")

  @JSImport("@tippyjs/react", JSImport.Default)
  @js.native
  private object TippyReactRaw extends js.Object

  val elementRef: Simple[HTMLElement] = Ref[HTMLElement]

  @js.native
  trait Props extends js.Object {
    var content: js.Any = js.native
    var interactive: Boolean = js.native
    var reference: js.Object = js.native
    var theme: String = js.native
    var maxWidth: js.Any = js.native
    var trigger: String = js.native
    var placement: String = js.native
    var plugins: js.UndefOr[js.Array[js.Any]] = js.native
    var onTrigger: js.Function2[TippyElement, Event, Unit] = js.native
  }

  def props(gaEventLabel: String, content: js.Object, interactive: Boolean, plugins: js.Array[js.Any], triggerEvent: String): Props = {
    val p = (new js.Object).asInstanceOf[Props]

    p.interactive = interactive
    p.content = content
    p.theme = "light-border"
    p.maxWidth = "None"
    p.trigger = triggerEvent
    p.placement = "top-end"
    p.plugins = plugins

    p.onTrigger = (el: TippyElement, event: Event) => {
      if (event.`type` == "show") {
        Callback(GoogleEventTracker.sendEvent("tooltip", s"show", gaEventLabel))
      } else if (event.`type` == "hide") {
        Callback(GoogleEventTracker.sendEvent("tooltip", "hide", gaEventLabel))
      }
    }

    p
  }

  val component = JsComponent[Props, Children.Varargs, Null](TippyReactRaw)

  def apply[A](gaEventLabel: String,
               content: js.Object,
               interactive: Boolean,
               trigger: VdomTagOf[HTMLElement],
               plugins: js.Array[js.Any] = js.Array(),
               triggerEvent: String = Tippy.TriggerEvents.focus
              ): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
    component(props(gaEventLabel, content, interactive, plugins, triggerEvent))(trigger)
}

@js.native
trait TippyElement extends js.Object {
  def hide(): Unit = js.native
}

class HideOnEscapeHooks(t: TippyElement) extends js.Object {

  def onShow(el: TippyElement): Unit = document
    .addEventListener("keydown", (event: KeyboardEvent) => {
      val escapeKeyCode = 27
      if (event.keyCode == escapeKeyCode) el.hide()
    })
}

class HideOnEsc() extends js.Object {

  def fn(h: TippyElement) = new HideOnEscapeHooks(h)

}

object Tippy extends ScalaCssReactImplicits {

  object TriggerEvents {
    val focus = "focus"
    val hover = "mouseenter"
    val focusAndHover = s"$hover $focus"
  }

  case class Props(gaEventLabel: String, content: VdomElement, interactive: Boolean, trigger: VdomNode, triggerEvent: String, maybeOnClick: Option[ReactEventFromInput => Callback]) extends UseValueEq

  val component = ScalaComponent.builder[Props]("TippyJs")
    .render_P(props => {
      val trigger = props.maybeOnClick match {
        case Some(onClick) => <.span(
          ^.className := "tooltip-trigger-onclick",
          ^.onClick ==> onClick,
          props.trigger)
        case None => props.trigger
      }
      val triggerWithTabIndex = <.span(
        ^.className := "tooltip-trigger",
        DefaultToolTipsStyle.triggerHoverIndicator,
        trigger,
        ^.tabIndex := 0
      )

      val plugins: js.Array[js.Any] = js.Array(new HideOnEsc())

      TippyJSComponent(props.gaEventLabel, props.content.rawElement, props.interactive, triggerWithTabIndex, plugins, props.triggerEvent)
    })
    .build

  def apply(gaEventLabel: String, content: VdomElement, interactive: Boolean, trigger: VdomNode, triggerEvent: String = TriggerEvents.focus, triggerCallback: Option[ReactEventFromInput => Callback] = None) =
    component(Props(gaEventLabel, content, interactive, trigger, triggerEvent, triggerCallback))

  def interactive(gaEventLabel: String, content: VdomElement, trigger: VdomNode) =
    apply(gaEventLabel, content, interactive = true, <.div(^.key := "trigger-wrapper", trigger))

  def describe(gaEventLabel: String, content: VdomElement, trigger: TagMod) =
    apply(gaEventLabel, content, interactive = false, <.span(trigger))

  def interactiveInfo(gaEventLabel: String, content: VdomElement, triggerCallback: Option[ReactEventFromInput => Callback] = None) =
    apply(gaEventLabel, content, interactive = true, trigger = <.span(^.className := "tippy-info-icon", ^.fontSize := "20px", MuiIcons(Info)(fontSize = "large")), triggerCallback = triggerCallback)

  def info(gaEventLabel: String, content: VdomElement) =
    apply(gaEventLabel, content, interactive = true, trigger = <.span(^.className := "tippy-info-icon", ^.fontSize := "20px", MuiIcons(Info)(fontSize = "large")))

  def info(gaEventLabel: String, content: String) =
    apply(gaEventLabel, <.div(content), interactive = true, trigger = <.span(^.className := "tippy-info-icon", ^.fontSize := "20px", MuiIcons(Info)(fontSize = "large")))

  def infoHover(gaEventLabel: String, content: String) =
    apply(gaEventLabel, <.div(content), interactive = true, trigger = <.span(^.className := "tippy-info-icon", ^.fontSize := "20px", MuiIcons(Info)(fontSize = "large")), triggerEvent = TriggerEvents.focusAndHover)

}


