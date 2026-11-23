package drt.client.services

import drt.client.logger.log
import japgolly.scalajs.react.Callback
import org.scalajs.dom

class StaffingChangesGuard {
  private var dirty: Boolean = false
  private var pending: Option[Callback] = None
  private var listener: Option[Boolean => Unit] = None

  def hasChanges: Boolean = dirty

  def markEdited(): Unit = dirty = true

  def markSaved(): Unit = dirty = false

  def abandon(): Unit = {
    dirty = false
    if (pending.nonEmpty) cancel()
  }

  def subscribe(onDialogChange: Boolean => Unit): Unit = listener = Some(onDialogChange)

  def unsubscribe(): Unit = {
    listener = None
    pending = None
  }

  /** Runs the action straight away if there are no unsaved changes, otherwise asks the user first. */
  def request(action: Callback): Callback = request(action, action)

  /** As above, but `confirmedAction` is what runs once the user chooses to discard their changes. */
  def request(action: Callback, confirmedAction: Callback): Callback = Callback {
    if (!dirty) action.runNow()
    else if (pending.isEmpty) {
      listener match {
        case Some(onDialogChange) =>
          pending = Some(confirmedAction)
          onDialogChange(true)
        case None =>
          log.error("Staffing confirmation dialog is not mounted")
          dom.window.alert("Unable to open the confirmation dialog. Please save your staff updates before leaving.")
      }
    }
  }

  def cancel(): Unit = {
    pending = None
    listener.foreach(_(false))
  }

  def confirm(): Unit = {
    val action = pending
    pending = None
    dirty = false
    listener.foreach(_(false))
    action.foreach(_.runNow())
  }
}

object StaffingChangesGuard extends StaffingChangesGuard
