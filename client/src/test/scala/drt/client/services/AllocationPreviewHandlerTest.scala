package drt.client.services

import diode.{ Action, ActionProcessor, ActionResult, Dispatcher }
import diode.data.Ready
import drt.client.actions.AllocationPreviewActions._
import drt.client.services.AllocationPreviewState.Context
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.AllocationPreviewHandler
import drt.shared.AllocationPreview.{ Request, Response, Result }
import drt.shared.FeatureFlags
import org.scalajs.dom
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import upickle.default.{ read, write }
import utest._

import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.timers.setTimeout
import scala.util.Try

object AllocationPreviewHandlerTest extends TestSuite {
  private case class ReplaceRoot(model: RootModel) extends Action
  private val requests = ListBuffer.empty[(String, String, String)]
  private val result = Result(Seq.empty, Seq.empty, Seq.empty, Map.empty)
  private val response = Response(0L, 60000L, Some(result), None)
  private var responseStatus = 200
  private var invalidResponse = false

  class FakeRequest extends js.Object {
    var status: Int = 0
    var readyState: Int = 0
    var responseText: String = ""
    var responseType: String = ""
    var timeout: Double = 0
    var withCredentials: Boolean = false
    var onreadystatechange: js.Function1[dom.Event, Unit] = (_: dom.Event) => ()
    var onload: js.Function1[dom.Event, Unit] = (_: dom.Event) => ()
    var onerror: js.Function1[dom.Event, Unit] = (_: dom.Event) => ()
    var ontimeout: js.Function1[dom.Event, Unit] = (_: dom.Event) => ()
    private var method = ""
    private var url = ""

    def open(verb: String, resource: String, async: js.UndefOr[Boolean] = js.undefined): Unit = {
      method = verb
      url = resource
    }
    def setRequestHeader(name: String, value: String): Unit = ()
    def send(body: js.Any): Unit = {
      requests += ((method, url, body.toString))
      status = responseStatus
      val request = read[Request](body.toString)
      responseText = if (invalidResponse) "{}"
      else write(if (request.kind == "recommended") response
      else response.copy(
        recommended = None,
        available = Some(result)
      ))
      readyState = 4
      val event = new dom.Event("load")
      onreadystatechange(event)
      onload(event)
    }
  }

  val tests: Tests = Tests {
    test("HTTP adapter posts only the selected kind and distinguishes connection loss from invalid responses") {
      val global = js.Dynamic.global.globalThis
      val previous = global.selectDynamic("XMLHttpRequest")
      global.updateDynamic("XMLHttpRequest")(js.constructorOf[FakeRequest])
      val csrf = dom.document.createElement("input").asInstanceOf[dom.html.Input]
      csrf.id = "csrfToken"
      csrf.value = "test"
      dom.document.body.appendChild(csrf)
      val portCode = dom.document.createElement("input")
      portCode.id = "port-code"
      portCode.setAttribute("value", "TEST")
      dom.document.body.appendChild(portCode)
      requests.clear()
      val context = Context(T1, SDate("2026-10-30T00:00Z").toLocalDate, eligible = true)
      val future = for {
        recommended <- AllocationPreviewHandler.calculate(context, 7L, "recommended")
        available <- AllocationPreviewHandler.calculate(context, 8L, "available")
        disconnected <- {
          responseStatus = 0
          AllocationPreviewHandler.calculate(context, 9L, "available")
        }
        malformed <- {
          responseStatus = 200
          invalidResponse = true
          AllocationPreviewHandler.calculate(context, 10L, "recommended")
        }
        notFound <- {
          responseStatus = 404
          invalidResponse = false
          AllocationPreviewHandler.calculate(context, 11L, "available")
        }
      } yield {
        assert(recommended == PreviewReceived(7L, response))
        assert(available == PreviewReceived(8L, response.copy(recommended = None, available = Some(result))))
        assert(disconnected == PreviewRequestFailed(9L, 0), malformed == PreviewRequestFailed(10L, -1))
        assert(notFound == PreviewRequestFailed(11L, 404))
        assert(requests.size == 5)
        requests.zip(Seq("recommended", "available", "available", "recommended", "available")).foreach {
          case ((method, url, body), kind) =>
            assert(method == "POST", url.endsWith("queue-allocation/neutral"))
            assert(read[Request](body) == Request(context.terminal, context.date, kind))
        }
      }
      val prepared = future.flatMap { _ =>
        responseStatus = 200
        val previousRoot = SPACircuit.zoom(identity).value
        val processor = new ActionProcessor[RootModel] {
          override def process(
              dispatch: Dispatcher,
              action: Any,
              next: Any => ActionResult[RootModel],
              model: RootModel
          ): ActionResult[RootModel] = action match {
            case ReplaceRoot(replacement) => ActionResult.ModelUpdate(replacement)
            case _                        => next(action)
          }
        }
        val user = LoggedInUser("test", "owner", "", Set(AccessOnlyPreprod, Debug, DesksAndQueuesView))
        SPACircuit.addProcessor(processor)
        SPACircuit.dispatch(ReplaceRoot(previousRoot.copy(
          featureFlags = Ready(FeatureFlags(false, false, false, false, false, enableQueueAllocationPreview = true)),
          loggedInUserPot = Ready(user),
          userHasPortAccess = Ready(true),
          viewMode = ViewDay(context.date, None),
          allocationPreview = AllocationPreviewState()
        )))
        SPACircuit.dispatch(SetPreviewContext(context.copy(ownerId = user.id)))
        (1 to 20).foreach(_ => SPACircuit.dispatch(PrepareNeutralAllocation("recommended")))
        val completed = Promise[Unit]()
        setTimeout(0) {
          completed.complete(Try {
            val state = SPACircuit.zoom(_.allocationPreview).value
            assert(!state.active, !state.pending, state.response.contains(response))
            SPACircuit.dispatch(PrepareNeutralAllocation("recommended"))
            assert(requests.size == 6)
            val root = SPACircuit.zoom(identity).value
            SPACircuit.dispatch(ReplaceRoot(root.copy(featureFlags =
              root.featureFlags.map(
                _.copy(enableQueueAllocationPreview = false)
              )
            )))
            SPACircuit.dispatch(PrepareNeutralAllocation("available"))
            assert(requests.size == 6)
          })
        }
        completed.future.andThen { case _ =>
          SPACircuit.dispatch(ReplaceRoot(previousRoot))
          SPACircuit.removeProcessor(processor)
        }
      }
      prepared.andThen { case _ =>
        responseStatus = 200
        invalidResponse = false
        global.updateDynamic("XMLHttpRequest")(previous)
        dom.document.body.removeChild(csrf)
        dom.document.body.removeChild(portCode)
      }
    }
  }
}
