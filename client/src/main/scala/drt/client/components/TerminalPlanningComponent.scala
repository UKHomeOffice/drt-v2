package drt.client.components

import diode.data.Pot
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.actions.Actions.GetForecastWeek
import drt.client.components.DropInDialog.StringExtended
import drt.client.components.styles.DrtTheme
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.{SendSelectedTimeInterval, SetMinStaffSuccessBanner}
import drt.client.services.{DrtApi, SPACircuit}
import drt.shared.CrunchApi.{ForecastPeriodWithHeadlines, ForecastTimeSlot, MillisSinceEpoch}
import drt.shared.Forecast
import io.kinoplan.scalajs.react.bridge.WithPropsAndTagsMods
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.callback.CallbackTo
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.vdom.all.onClick.Event
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.Select
import org.scalajs.dom.{Blob, HTMLAnchorElement, URL, document}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliDate, SDateLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

object TerminalPlanningComponent {

  case class TerminalPlanningModel(forecastPeriodPot: Pot[ForecastPeriodWithHeadlines])

  def getLastSunday(start: SDateLike): SDateLike = {
    val sunday = start.getLastSunday

    SDate(f"${sunday.getFullYear}-${sunday.getMonth}%02d-${sunday.getDate}%02dT00:00:00")
  }

  case class Props(page: TerminalPageTabLoc, router: RouterCtl[Loc], timePeriod: Int)

  private val forecastWeeks: Seq[SDateLike] = (-4 to 30).map(w => getLastSunday(SDate.now()).addDays(w * 7))

  case class State(downloadingHeadlines: Boolean, downloadingStaff: Boolean, timePeriod: Int)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalForecast")
    .initialStateFromProps(p => State(downloadingHeadlines = false, downloadingStaff = false, timePeriod = p.timePeriod))
    .renderPS((scope, props, state) => {
      val modelRCP = SPACircuit.connect(model => TerminalPlanningModel(forecastPeriodPot = model.forecastPeriodPot))
      modelRCP(modelProxy => {
        val model: TerminalPlanningModel = modelProxy()
        <.div(model.forecastPeriodPot.renderReady { forecastPeriod =>
          val sortedDays = forecastPeriod.forecast.days.toList.sortBy(_._1)
          val byTimeSlot: Seq[List[Option[ForecastTimeSlot]]] = Forecast.periodByTimeSlotAcrossDays(forecastPeriod.forecast)

          def drawSelect(names: Seq[String], values: List[String], value: String): VdomTagOf[Select] = {
            <.select(^.className := "form-control", ^.value := value,
              ^.onChange ==> ((e: ReactEventFromInput) => {
                props.router.set(props.page.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toLocalDate.toISOString))))
              }),
              values.zip(names).map {
                case (value, name) => <.option(^.value := value, name)
              }.toTagMod)
          }

          val slotStartTimes = Forecast.timeSlotStartTimes(
            forecastPeriod.forecast,
            (millis: MillisSinceEpoch) => SDate(millis).toHoursAndMinutes
          )

          def downloadContent(content: String, filename: String): Unit = {
            val a = document.createElement("a").asInstanceOf[HTMLAnchorElement]
            a.setAttribute("href", URL.createObjectURL(new Blob(js.Array(content))))
            a.setAttribute("download", filename)
            a.click()
          }

          val headlineFiguresExportUrl = s"export/headlines/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}"
          val staffRecommendationsExportUrl = s"export/planning/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}"

          def createDownload(updateState: (State, Boolean) => State): String => Event => CallbackTo[Unit] = url => {
            event =>
              event.preventDefault()
              scope.modState(s => updateState(s, true)) >>
                Callback {
                  val direct = scope.withEffectsImpure
                  DrtApi.get(url)
                    .map { r =>
                      val fileName = r.getResponseHeader("Content-Disposition").split("filename=")(1)
                      downloadContent(r.responseText, fileName)
                      direct.modState(s => updateState(s, false))
                    }
                    .recover {
                      case _ => direct.modState(s => updateState(s, false))
                    }
                }
          }

          <.div(
            <.h3(^.style := js.Dictionary("size" -> "24px", "color" -> DrtTheme.theme.palette.primary.`700`), "Headline Figures"),
            <.div(^.className := "terminal-content-header",
              <.div(^.className := "staffing-controls-wrapper",
                <.div(^.className := "staffing-controls-row",
                  <.label(^.className := "staffing-controls-label", "Select week start day"),
                  <.div(^.className := "staffing-controls-select",
                    drawSelect(
                      forecastWeeks.map(_.ddMMyyString),
                      forecastWeeks.map(_.toISOString).toList,
                      defaultStartDate(props.page.dateFromUrlOrNow).toISOString)
                  )
                ),
                MuiDivider()(),
                <.div(^.className := "staffing-controls-row",
                  buttonWithProgress(headlineFiguresExportUrl,
                    buttonContent(state.downloadingHeadlines, "Export Headlines"),
                    createDownload((s: State, b: Boolean) => s.copy(downloadingHeadlines = b)),
                    state.downloadingHeadlines,
                  ),
                  buttonWithProgress(staffRecommendationsExportUrl,
                    buttonContent(state.downloadingStaff, "Export Staff Requirements"),
                    createDownload((s: State, b: Boolean) => s.copy(downloadingStaff = b)),
                    state.downloadingStaff
                  ),
                )
              ),
            ),
            <.table(^.className := "headlines",
              <.thead(
                <.tr(
                  <.th(^.className := "queue-heading"),
                  forecastPeriod.headlines.queueDayHeadlines.map(_.day).toSet.toList.sorted.map(
                    day => <.th(s"${SDate(MilliDate(day)).getDate}/${SDate(MilliDate(day)).getMonth}")
                  ).toTagMod
                ), {
                  val groupedByQ = forecastPeriod.headlines.queueDayHeadlines.groupBy(_.queue)
                  Queues.queueOrder.flatMap(q => groupedByQ.get(q).map(qhls => <.tr(
                    <.th(^.className := "queue-heading", s"${Queues.displayName(q)}"), qhls.toList.sortBy(_.day).map(qhl => <.td(qhl.paxNos)).toTagMod
                  ))).toTagMod
                }, {
                  val byDay = forecastPeriod.headlines.queueDayHeadlines.groupBy(_.day).toList
                  List(
                    <.tr(^.className := "total",
                      <.th(^.className := "queue-heading", "Total Pax"), byDay.sortBy(_._1).map(hl => <.th(hl._2.map(_.paxNos).sum)).toTagMod),
                    <.tr(^.className := "total",
                      <.th(^.className := "queue-heading", "Workloads"), byDay.sortBy(_._1).map(hl => <.th(hl._2.map(_.workload).sum)).toTagMod)
                  ).toTagMod
                }
              )
            ),
            <.h3(^.style := js.Dictionary("size" -> "24px", "color" -> DrtTheme.theme.palette.primary.`700`), "Available and recommended staff"),
            MuiFormControl()(
              <.div(^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center"),
                MuiFormLabel(sx = SxProps(Map("size" -> "16px",
                  "paddingRight" -> "10px",
                  "color" -> DrtTheme.theme.palette.grey.`900`,
                  "fontWeight" -> "bold")))(<.span("Time Period")),
                MuiRadioGroup(row = true)(^.value := state.timePeriod, ^.onChange ==> ((e: ReactEventFromInput) => {
                  scope.modState(_.copy(timePeriod = e.target.value.toInt)) >>
                    Callback(SPACircuit.dispatch(SendSelectedTimeInterval(e.target.value.toInt))) >>
                    Callback(SPACircuit.dispatch(GetForecastWeek(props.page.dateFromUrlOrNow, Terminal(props.page.terminalName), e.target.value.toInt)))
                }), MuiFormControlLabel(control = MuiRadio()().rawElement, label = "Hourly".toVdom)(^.value := "60"),
                  MuiFormControlLabel(control = MuiRadio()().rawElement, label = "Every 15 minutes".toVdom)(^.value := "15")
                ))
            ),
            <.table(^.className := "forecast",
              <.thead(^.className := "sticky-top",
                <.tr(
                  <.th(^.className := "heading"), sortedDays.map {
                    case (day, _) =>
                      <.th(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(day)).getDate}/${SDate(MilliDate(day)).getMonth}")
                  }.toTagMod
                ),
                <.tr(
                  <.th(^.className := "heading", "Time"),
                  sortedDays.flatMap(_ => List(<.th(^.className := "sub-heading", "Avail"), <.th(^.className := "sub-heading", "Rec"))).toTagMod
                )),
              <.tbody(
                byTimeSlot.zip(slotStartTimes).map {
                  case (row, startTime) =>
                    <.tr(
                      <.td(s"$startTime"),
                      row.flatMap {
                        case Some(col) =>
                          val ragClass = TerminalDesksAndQueuesRow.ragStatus(col.required, col.available)
                          List(<.td(^.className := ragClass, col.available), <.td(col.required))
                        case None =>
                          List(<.td(), <.td())
                      }.toTagMod
                    )
                }.toTagMod
              )
            )
          )
        })
      })
    })
    .componentDidMount(p =>
      Callback(SPACircuit.dispatch(GetForecastWeek(p.props.page.dateFromUrlOrNow, Terminal(p.props.page.terminalName), p.props.timePeriod))) >>
        Callback(SPACircuit.dispatch(SetMinStaffSuccessBanner(false))) >>
        Callback {
          GoogleEventTracker.sendPageView(s"${p.props.page.terminal}/planning/${defaultStartDate(p.props.page.dateFromUrlOrNow).toISODateOnly}")
        })
    .build

  private def buttonContent(isPreparing: Boolean, labelText: String): Seq[VdomNode] =
    if (isPreparing)
      Seq(MuiCircularProgress(size = "28px")(), "Preparing... please wait")
    else
      Seq(MuiIcons(GetApp)(fontSize = "small"), labelText)

  private def buttonWithProgress(url: String,
                                 label: Seq[TagMod],
                                 createDownload: String => Event => CallbackTo[Unit],
                                 disabled: Boolean,
                                ): WithPropsAndTagsMods =
    MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
      ^.disabled := disabled,
      <.div(^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center", "gap" -> "15px"), label.toTagMod),
      ^.className := "btn btn-link muiButton",
      ^.href := SPAMain.absoluteUrl(url),
      ^.target := "_blank",
      ^.onClick ==> createDownload(url)
    )

  def defaultStartDate(date: SDateLike): SDateLike = getLastSunday(date)

  def apply(props: Props): VdomElement = component(props)
}
