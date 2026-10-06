package drt.client.components

import org.scalajs.dom
import org.scalajs.dom.html
import utest._

import scala.scalajs.js

object FocusTrackerTests extends TestSuite {
  private def dispatch(eventType: String, target: dom.EventTarget = dom.document): Unit = {
    val event = dom.document.createEvent("Event")
    event.asInstanceOf[js.Dynamic].initEvent(eventType, true, true)
    target.dispatchEvent(event)
  }

  private def keydown(target: dom.EventTarget, keyValue: String): Unit = {
    val constructor = dom.window.asInstanceOf[js.Dynamic].KeyboardEvent
    val event = js.Dynamic.newInstance(constructor)("keydown", js.Dynamic.literal("key" -> keyValue, "bubbles" -> true))
    target.dispatchEvent(event.asInstanceOf[dom.KeyboardEvent])
  }

  private def button(id: String): html.Button = {
    val element = dom.document.createElement("button").asInstanceOf[html.Button]
    element.id = id
    dom.document.body.appendChild(element)
    element
  }

  private def remove(element: html.Element): Unit = element.parentNode.removeChild(element)

  def tests: Tests = Tests {
    FocusTracker.init()

    test("restores keyboard-originated focus when it is lost") {
      FocusTracker.resetForTests()
      val element = button("keyboard-focus-target")

      dispatch("keydown")
      element.focus()
      element.blur()
      FocusTracker.restore()

      assert(dom.document.activeElement == element)
      remove(element)
    }

    test("does not restore stale focus after pointer interaction") {
      FocusTracker.resetForTests()
      val element = button("pointer-focus-target")

      dispatch("keydown")
      element.focus()
      dispatch("pointerdown")
      element.blur()
      FocusTracker.restore()

      assert(dom.document.activeElement != element)
      remove(element)
    }

    test("does not restore stale focus after mouse interaction") {
      FocusTracker.resetForTests()
      val element = button("mouse-focus-target")

      dispatch("keydown")
      element.focus()
      dispatch("mousedown")
      element.blur()
      FocusTracker.restore()

      assert(dom.document.activeElement != element)
      remove(element)
    }

    test("does not record focus that originated from a pointer") {
      FocusTracker.resetForTests()
      val element = button("pointer-only-focus-target")

      element.focus()
      element.blur()
      FocusTracker.restore()

      assert(dom.document.activeElement != element)
      remove(element)
    }

    test("does not restore a keyboard-focused element without an id") {
      FocusTracker.resetForTests()
      val element = button("")

      dispatch("keydown")
      element.focus()
      element.blur()
      FocusTracker.restore()

      assert(dom.document.activeElement != element)
      remove(element)
    }

    test("ignores a keyboard focus target removed during rendering") {
      FocusTracker.resetForTests()
      val element = button("removed-focus-target")

      dispatch("keydown")
      element.focus()
      remove(element)
      FocusTracker.restore()

      assert(dom.document.activeElement != element)
    }

    test("clears the previous target when keyboard focus moves to an element without an id") {
      FocusTracker.resetForTests()
      val previous = button("previous-focus-target")
      val next = button("")

      try {
        keydown(dom.document, "Tab")
        previous.focus()
        keydown(previous, "Tab")
        next.focus()
        next.blur()
        FocusTracker.restore()

        assert(dom.document.activeElement == dom.document.body)
      } finally {
        remove(previous)
        remove(next)
      }
    }

    test("restores a keyboard-focused replacement with the same id") {
      FocusTracker.resetForTests()
      val original = button("replacement-focus-target")

      keydown(dom.document, "Tab")
      original.focus()
      remove(original)
      val replacement = button("replacement-focus-target")

      try {
        FocusTracker.restore()
        assert(dom.document.activeElement == replacement)
      } finally remove(replacement)
    }

    test("restores focus when a pointer-focused control is subsequently activated with the keyboard") {
      for (key <- Seq(" ", "Enter", "ArrowRight")) {
        FocusTracker.resetForTests()
        val original = button("mixed-input-focus-target")

        dispatch("mousedown", original)
        original.focus()
        keydown(original, key)
        remove(original)
        val replacement = button("mixed-input-focus-target")

        try {
          FocusTracker.restore()
          assert(dom.document.activeElement == replacement)
        } finally remove(replacement)
      }
    }

    test("does not steal focus from another active control") {
      FocusTracker.resetForTests()
      val previous = button("active-previous-target")
      val next = button("")

      try {
        keydown(dom.document, "Tab")
        previous.focus()
        next.focus()
        FocusTracker.restore()

        assert(dom.document.activeElement == next)
      } finally {
        remove(previous)
        remove(next)
      }
    }

    test("restores focus with preventScroll enabled") {
      FocusTracker.resetForTests()
      val element = button("non-scrolling-focus-target")
      var focusCalls = 0
      var preventScroll = false

      try {
        keydown(dom.document, "Tab")
        element.focus()
        element.blur()
        element.asInstanceOf[js.Dynamic].focus = ((options: js.Dynamic) => {
          focusCalls += 1
          preventScroll = options.preventScroll.asInstanceOf[Boolean]
        }): js.Function1[js.Dynamic, Unit]
        FocusTracker.restore()

        assert(focusCalls == 1, preventScroll)
      } finally remove(element)
    }

    test("records keyboard focus before a component handler removes the control and stops propagation") {
      FocusTracker.resetForTests()
      val original = button("keydown-handler-focus-target")

      original.focus()
      original.addEventListener("keydown", (event: dom.KeyboardEvent) => {
        event.stopPropagation()
        remove(original)
      })
      keydown(original, "Enter")
      val replacement = button("keydown-handler-focus-target")

      try {
        FocusTracker.restore()
        assert(dom.document.activeElement == replacement)
      } finally remove(replacement)
    }

    test("clears pointer focus before a component handler restores focus and stops propagation") {
      for (eventType <- Seq("pointerdown", "mousedown", "touchstart")) {
        FocusTracker.resetForTests()
        val previous = button("capture-previous-target")
        val clicked = button("capture-clicked-target")

        try {
          keydown(dom.document, "Tab")
          previous.focus()
          clicked.addEventListener(eventType, (event: dom.Event) => {
            event.stopPropagation()
            previous.blur()
            FocusTracker.restore()
          })
          dispatch(eventType, clicked)

          assert(dom.document.activeElement == dom.document.body)
        } finally {
          remove(previous)
          remove(clicked)
        }
      }
    }
  }
}
