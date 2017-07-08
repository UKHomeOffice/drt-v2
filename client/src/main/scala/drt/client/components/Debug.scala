package drt.client.components

import diode.data.{Pot, Ready}
import diode.react.ModelProxy
import drt.client.SPAMain.Loc
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel.FlightCode
import drt.client.services._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PassengerSplits.VoyagePaxSplits
import drt.shared.{ApiFlightWithSplits, MilliDate, StaffMovement}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom

import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.util.Try

object Debug {

  case class Props()

  val component = ScalaComponent.builder[Props]("Debug")
    .render_P(p => {
      val staffingRCP = SPACircuit.connect(
        m => (
          m.shiftsRaw,
          m.fixedPointsRaw,
          m.staffMovements,
          m.workloadPot,
          m.queueCrunchResults,
          m.simulationResult,
          m.flightsWithSplitsPot,
          m.flightSplits
        )
      )
      staffingRCP((staffingMP: ModelProxy[(
        Pot[String],
          Pot[String],
          Pot[Seq[StaffMovement]],
          Pot[Workloads],
          RootModel.TerminalQueueCrunchResults,
          RootModel.TerminalQueueSimulationResults,
          Pot[FlightsWithSplits],
          Map[FlightCode, Map[MilliDate, VoyagePaxSplits]]
        )]) => {
        val (potShifts, potFixedPoints, staffMovements, potWorkloads, tqcr, simulationResult, potFlights, flightSplits) = staffingMP()

        if (dom.window.hasOwnProperty("debug")) {
          <.table(
            <.tr(<.th("shifts"), potShifts.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s)))),
            <.tr(<.th("fixed points"), potFixedPoints.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s)))),
            <.tr(<.th("staff movements"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), staffMovements.toString()))),
            <.tr(<.th("Terminal queue Crunch Results"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), tqcr.toString()))),
            <.tr(<.th("Simulation Result"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), simulationResult.toString()))),
            <.tr(<.th("workloads"), potWorkloads.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.toString)))),
            <.tr(<.th("flights with splits"), potFlights.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.toString))))
          )
        } else {
          <.div()
        }
      })
    }
    ).build

  def apply(): VdomElement = component(Props())
}
