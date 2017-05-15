package drt.client.components

import scalacss.DevDefaults._
import scalacss.internal.StyleLookup
//import scalacss.internal.mutable
import drt.client.components.Bootstrap.CommonStyle._

object BootstrapStyles extends StyleSheet.Inline {

  import scalacss.internal.StyleLookup.scalaMap
  import dsl._

  val csDomain = Domain.ofValues(default, primary, success, info, warning, danger)

  val contextDomain = Domain.ofValues(success, info, warning, danger)

  def commonStyle[A](domain: Domain[A], base: String)(implicit l: StyleLookup[A]) = styleF(domain)(opt =>
    styleS(addClassNames(base, s"$base-$opt"))(cssComposition)
  )

  def styleWrap(classNames: String*) = style(addClassNames(classNames: _*))

  val buttonOpt = commonStyle(csDomain, "btn")

  val button = buttonOpt(default)

  val panelOpt: (Bootstrap.CommonStyle.Value) => StyleA = commonStyle(csDomain, "panel")

  val panel = panelOpt(default)

  val labelOpt = commonStyle(csDomain, "label")

  val labelDanger = Map("label" -> true, "label-danger" -> true)
  val label = labelOpt(default)

  val alert = commonStyle(contextDomain, "alert")

  val panelHeadingStr = "panel-heading"
  val panelHeading = styleWrap(panelHeadingStr)

  val panelBodyStr = "panel-body"
  val panelBody = styleWrap(panelBodyStr)

  // wrap styles in a namespace, assign to val to prevent lazy initialization
  object modal {
    val modal = styleWrap("modal")
    val fade = styleWrap("fade")
    val dialog = styleWrap("modal-dialog")
    val content = styleWrap("modal-content")
    val header = styleWrap("modal-header")
    val body = styleWrap("modal-body")
    val footer = styleWrap("modal-footer")
  }

  val _modal = modal

  object listGroup {
    val listGroup = styleWrap("list-group")
    val item = styleWrap("list-group-item")
    val itemOpt = commonStyle(contextDomain, "list-group-item")
  }

  val _listGroup = listGroup
  val pullRight = styleWrap("pull-right")
  val buttonXS = styleWrap("btn-xs")
  val close = styleWrap("close")

  val labelAsBadgeCls = "label-as-badge"
  val labelAsBadge = style(addClassName(labelAsBadgeCls), borderRadius(1.em))
  val navbarClsSet = Seq("nav", "navbar-nav")
  val navbar = styleWrap("nav", "navbar-nav")

  val formGroup = styleWrap("form-group")
  val formControl = styleWrap("form-control")
}
