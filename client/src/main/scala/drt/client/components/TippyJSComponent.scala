//package drt.client.components
//
//import com.payalabs.scalajs.react.bridge.{ReactBridgeComponent, WithProps}
//import drt.client.logger.{Logger, LoggerFactory}
//import japgolly.scalajs.react.{Children, CtorType, JsComponent}
//import japgolly.scalajs.react.component.Js.{Component, RawMounted, UnmountedWithRawType}
//import japgolly.scalajs.react.raw.{ReactDOMElement, ReactNode}
//import japgolly.scalajs.react.vdom.VdomNode
//import org.scalajs.dom.html
//import org.scalajs.dom.html.Div
//
//import scala.scalajs.js
//import scala.scalajs.js.annotation.JSImport
//
//object TippyJSComponent extends ReactBridgeComponent {
//
//  val log: Logger = LoggerFactory.getLogger("TippyJSComponent")
//
//    @JSImport("@tippyjs/react", JSImport.Namespace, "Tippy")
//    @js.native
//    object Tippy extends Tippy
//
//    @js.native
//    trait Tippy extends js.Object {
//      def tippy(element: ReactNode): ReactDOMElement = js.native
//    }
//
//    @js.native
//    trait Props extends js.Object {
//      var content: VdomNode
//    }
//
//    def props(content: VdomNode): Props = {
//
//      val props = (new js.Object).asInstanceOf[Props]
//
//      props.content = content
//
//      props
//    }
//
//    private def component(element: VdomNode): Component[Props, Props, CtorType.PropsAndChildren] = {
//
//      JsComponent[Props, Children.Varargs, Props](Tippy.tippy(element.rawNode))
//    }
//
//    def apply(content: VdomNode, children: VdomNode*): UnmountedWithRawType[Props, Props, RawMounted] = {
//  //    log.info(s"The component $component" )
//  //    log.info(s"Tippy $Tippy" )
//  //    log.info(s"Tippy ${Tippy.tippy}" )
//      scala.scalajs.js.special.debugger()
//      component(content)(props(content))(children: _*)
//    }
//
//
//}
