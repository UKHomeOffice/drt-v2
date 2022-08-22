package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{NodeListSeq, documentScrollHeight, documentScrollTop}
import japgolly.scalajs.react.Callback
import org.scalajs.dom
import org.scalajs.dom.{Element, Event, NodeListOf}

import scala.util.{Success, Try}

object StickyTableHeader {
  def toIntOrElse(intString: String, stickyInitial: Int): Int = {
    Try {
      intString.toDouble.round.toInt
    } match {
      case Success(x) => x
      case _ => stickyInitial
    }
  }

  def handleStickyClass(top: Double,
                        bottom: Double,
                        elements: NodeListSeq[Element],
                        toStick: Element): Unit = {
    elements.foreach(sticky => {
      val stickyEnter = toIntOrElse(sticky.getAttribute("data-sticky-initial"), 0)
      val stickyExit = bottom.round.toInt

      if (top >= stickyEnter && top <= stickyExit)
        toStick.classList.add("sticky-show")
      else toStick.classList.remove("sticky-show")
    })
  }

  def setInitialHeights(elements: NodeListSeq[Element]): Unit = {
    elements.foreach(element => {
      val scrollTop = documentScrollTop
      val relativeTop = element.getBoundingClientRect().top
      val actualTop = relativeTop + scrollTop
      element.setAttribute("data-sticky-initial", actualTop.toString)
    })
  }

  def apply(selector: String): Callback = {

    val stickies: NodeListSeq[Element] = dom.document.querySelectorAll(selector).asInstanceOf[NodeListOf[Element]]

    dom.document.addEventListener("scroll", (_: Event) => {
      val top = documentScrollTop
      val bottom = documentScrollHeight
      Option(dom.document.querySelector("#sticky-body")).foreach { _ =>
        handleStickyClass(top, bottom, stickies, dom.document.querySelector("#toStick"))
      }
    })

    Callback(setInitialHeights(stickies))
  }
}
