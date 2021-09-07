package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.components.ToolTips._
import drt.client.modules.GoogleEventTracker
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared._
import drt.shared.redlist.RedListUpdates
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.RedListsEdit

object PortConfigPage {

  case class Props(redListUpdates: Pot[RedListUpdates], user: Pot[LoggedInUser], airportConfig: Pot[AirportConfig]) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ConfigPage")
    .render_P { props =>
      val mp = for {
        redListUpdates <- props.redListUpdates
        user <- props.user
        airportConfig <- props.airportConfig
      } yield
        <.div(
          ^.className := "port-config",
          <.h3("Port Config"),
          if (user.hasRole(RedListsEdit)) RedListEditor(redListUpdates) else EmptyVdom,
          PortConfigDetails(airportConfig)
        )
      mp.render(identity)
    }
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"port-config")
    })
    .build

  def apply(props: Props): VdomElement = component(props)
}

object PortConfigDetails {

  case class Props(airportConfig: AirportConfig) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ConfigDetails")
    .render_P { props =>
      <.div(
        props.airportConfig.terminals.map(tn =>
          <.div(
            <.h2(tn.toString),
            <.div(^.className := "container config-container",
              <.h4("Min / Max Desks or eGate Banks by hour of day"),
              minMaxDesksTable(props.airportConfig.minMaxDesksByTerminalQueue24Hrs(tn))
            ),
            <.div(^.className := "container config-container",
              <.h4("Default Processing Times", " ", defaultProcessingTimesTooltip),
              defaultProcessingTimesTable(props.airportConfig.terminalProcessingTimes(tn))
            ),
            <.div(^.className := "container config-container",
              <.h4("Passenger Queue Allocation"),
              defaultPaxSplits(props.airportConfig.terminalPaxTypeQueueAllocation(tn))
            ),
            <.div(^.className := "container config-container",
              <.h4("Walktimes", " ", walkTimesTooltip),
              defaultWalktime(props.airportConfig.defaultWalkTimeMillis(tn))
            )
          )
        ).toTagMod
      )
    }
    .build

  def minMaxDesksTable(minMaxDesksByTerminalQueue: Map[Queue, (List[Int], List[Int])]): TagMod = minMaxDesksByTerminalQueue.map {
    case (queue, (min, max)) =>
      <.div(^.className := "config-block float-left",
        <.h4(Queues.displayName(queue)),
        <.table(^.className := "table table-bordered table-hover", <.tbody(
          <.tr(
            <.th(^.className := "col", "Hour"),
            <.th(^.className := "col", "Min"),
            <.th(^.className := "col", "Max")
          ),
          min.zip(max).zipWithIndex.map {
            case ((mi, ma), hourOfDay) =>
              <.tr(
                <.th(^.scope := "row", f"$hourOfDay%02d:00"),
                <.td(^.className := "text-right", mi),
                <.td(^.className := "text-right", ma)
              )
          }.toTagMod
        ))
      )
  }.toTagMod

  def defaultProcessingTimesTable(defaultProcessingTimes: Map[PaxTypeAndQueue, Double]): VdomTagOf[Div] = <.div(^.className := "config-block float-left",
    <.table(^.className := "table table-bordered table-hover",
      <.tbody(
        <.tr(
          <.th(^.className := "col", "Passenger Type / Queue"),
          <.th(^.className := "col", "Seconds")
        ),
        defaultProcessingTimes
          .toList
          .sortBy {
            case (paxTypeAndQueue, _) => paxTypeAndQueue.queueType.toString + paxTypeAndQueue.passengerType
          }
          .map {
            case (ptq, time) =>
              <.tr(
                <.th(^.scope := "row", s"${ptq.displayName}"),
                <.td(^.className := "text-right", (time * 60).toInt)
              )
          }.toTagMod
      )
    )
  )

  def defaultWalktime(defaultWalkTime: MillisSinceEpoch): VdomTagOf[Div] = <.div(^.className := "config-block float-left",
    <.table(^.className := "table table-bordered table-hover",
      <.tbody(
        <.tr(
          <.th(^.className := "col", "Gate"),
          <.th(^.className := "col", "Walk time minutes")
        ),
        <.tr(
          <.th(^.scope := "row", "Default"),
          <.td(^.className := "text-right", (defaultWalkTime / 60000).toInt)
        )

      )
    )
  )

  def defaultPaxSplits(defaultPaxTypeQueueAllocation: Map[PaxType, Seq[(Queue, Double)]]): VdomTagOf[Div] = <.div(^.className := "config-block float-left",
    <.table(^.className := "table table-bordered table-hover",
      <.tbody(
        <.tr(
          <.th(^.className := "col", "Passenger Type"),
          <.th(^.className := "col", "Queue"),
          <.th(^.className := "col", "Allocation")
        )
        ,
        defaultPaxTypeQueueAllocation
          .toList
          .sortBy {
            case (pt, _) => pt.cleanName
          }.flatMap {
          case (pt, list) =>
            list.map {
              case (qt, ratio) =>
                <.tr(
                  <.td(^.scope := "row", PaxTypes.displayName(pt)),
                  <.td(^.scope := "row", Queues.displayName(qt)),
                  <.td(^.className := "text-right", s"${Math.round(ratio * 100)}%")
                )
            }
        }.toTagMod
      )
    )
  )

  def apply(airportConfig: AirportConfig): VdomElement = component(Props(airportConfig))
}

