package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.RemoveArrivalSources
import drt.client.components.ArrivalsExportComponent.StringExtended
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.ToolTips._
import drt.client.services._
import drt.shared._
import drt.shared.api.WalkTimes
import io.kinoplan.scalajs.react.bridge.WithProps.toVdomNode
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.ExpandMore
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.component.ScalaFn
import japgolly.scalajs.react.facade.React.Node
import japgolly.scalajs.react.hooks.Hooks.UseState
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.{TableCell, TableSection}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet
import scala.scalajs.js

object FlightTable {
  case class Props(flightsWithSplits: List[ApiFlightWithSplits],
                   queueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean,
                   displayRedListInfo: Boolean,
                   redListOriginWorkloadExcluded: Boolean,
                   terminal: Terminal,
                   portCode: PortCode,
                   redListPorts: HashSet[PortCode],
                   redListUpdates: RedListUpdates,
                   airportConfig: AirportConfig,
                   walkTimes: WalkTimes,
                  ) extends UseValueEq

  case class State(nationalityFlaggerOpen: Boolean)

  def apply(shortLabel: Boolean = false,
            timelineComponent: Option[Arrival => VdomNode] = None,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .initialState(State(nationalityFlaggerOpen = false))
    .renderPS { (modState, props, state) =>
      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod

      if (sortedFlights.nonEmpty) {
        val redListPaxExist = sortedFlights.exists(_._1.apiFlight.RedListPax.exists(_ > 0))
        val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
          "* Passengers from CTA & Red List origins do not contribute to PCP workload"
        else
          "* Passengers from CTA origins do not contribute to PCP workload"

        <.div(
          (props.loggedInUser.hasRole(ArrivalSource), props.arrivalSources) match {
            case (true, Some((_, sourcesPot))) =>
              <.div(^.tabIndex := 0,
                <.div(^.className := "popover-overlay", ^.onClick --> Callback(SPACircuit.dispatch(RemoveArrivalSources))),
                <.div(^.className := "dashboard-arrivals-popup", ArrivalInfo.SourcesTable(ArrivalInfo.Props(sourcesPot, props.airportConfig)))
              )
            case _ => <.div()
          },
          NationalityFlaggingComponment()(NationalityFlaggingComponment.Props(state.nationalityFlaggerOpen, (expanded: Boolean) => modState.modState(_.copy(nationalityFlaggerOpen = expanded)))),
          <.table(
            ^.className := "arrivals-table table-striped",
            tableHead(props, timelineTh, props.queueOrder, redListPaxExist, shortLabel),
            <.tbody(
              sortedFlights.zipWithIndex.map {
                case ((flightWithSplits, codeShares), idx) =>
                  val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                  val directRedListFlight = redlist.DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch, props.portCode, props.terminal, flightWithSplits.apiFlight.Terminal, isRedListOrigin)
                  val redListPaxInfo = redlist.IndirectRedListPax(props.displayRedListInfo, flightWithSplits)
                  FlightTableRow.component(FlightTableRow.Props(
                    flightWithSplits = flightWithSplits,
                    codeShares = codeShares,
                    idx = idx,
                    timelineComponent = timelineComponent,
                    originMapper = originMapper,
                    splitsGraphComponent = splitsGraphComponent,
                    splitsQueueOrder = props.queueOrder,
                    hasEstChox = props.hasEstChox,
                    loggedInUser = props.loggedInUser,
                    viewMode = props.viewMode,
                    defaultWalkTime = props.defaultWalkTime,
                    hasTransfer = props.hasTransfer,
                    indirectRedListPax = redListPaxInfo,
                    directRedListFlight = directRedListFlight,
                    airportConfig = props.airportConfig,
                    redListUpdates = props.redListUpdates,
                    includeIndirectRedListColumn = redListPaxExist,
                    walkTimes = props.walkTimes,
                  ))
              }.toTagMod)
          ),
          excludedPaxNote
        )
      }
      else <.div("No flights to display")
    }
    .build

  def tableHead(props: Props, timelineTh: TagMod, queues: Seq[Queue], redListPaxExist: Boolean, shortLabel: Boolean): TagOf[TableSection] = {
    val redListHeading = "Red List Pax"
    val isMobile = dom.window.innerWidth < 800
    val columns = columnHeaders(shortLabel, redListHeading, isMobile)

    val portColumnThs = columnHeadersWithClasses(columns, props.hasEstChox, props.displayRedListInfo, redListPaxExist, redListHeading)
      .toTagMod

    val queueDisplayNames = queues.map { q =>
      val queueName: String = Queues.displayName(q)
      <.th(queueName, " ", splitsTableTooltip)
    }.toTagMod

    val transferPaxTh = <.th("Transfer Pax")

    <.thead(
      ^.className := "sticky-top",
      if (props.hasTransfer) {
        <.tr(
          timelineTh,
          portColumnThs,
          queueDisplayNames,
          transferPaxTh
        )
      } else {
        <.tr(
          timelineTh,
          portColumnThs,
          queueDisplayNames
        )
      }
    )
  }

  private def columnHeadersWithClasses(columns: List[(String, Option[String])],
                                       hasEstChox: Boolean,
                                       displayRedListInfo: Boolean,
                                       redListPaxExist: Boolean,
                                       redListHeading: String): List[VdomTagOf[TableCell]] = {
    val estChoxHeading = "Est Chox"
    columns
      .filter {
        case (label, _) => label != estChoxHeading || hasEstChox
      }
      .filter {
        case (label, _) => label != redListHeading || (displayRedListInfo && redListPaxExist)
      }
      .map {
        case (label, Some(className)) if label == "Est PCP Pax" => <.th(
          <.div(^.cls := className, label, " ", totalPaxTooltip)
        )
        case (label, None) if label == "Expected" || label == "Exp" => <.th(
          <.div(label, " ", expTimeTooltip)
        )
        case (label, None) if label == "Flight" => <.th(
          <.div(^.cls := "arrivals__table__flight-code-wrapper", label, " ", wbrFlightColorTooltip)
        )
        case (label, None) => <.th(label)
        case (label, Some(className)) if className == "status" => <.th(label, " ", arrivalStatusTooltip, ^.className := className)
        case (label, Some(className)) if className == "gate-stand" => <.th(label, " ", gateOrStandTh, ^.className := className)
        case (label, Some(className)) if className == "country" => <.th(label, " ", countryTooltip, ^.className := className)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
  }

  private def columnHeaders(shortLabel: Boolean, redListHeading: String, isMobile: Boolean) = {
    List(
      ("Flight", Option("arrivals__table__flight-code")),
      (if (isMobile) "Ori" else "Origin", None),
      ("Country", Option("country")),
      (redListHeading, None),
      (if (isMobile || shortLabel) "Gt/St" else "Gate / Stand", Option("gate-stand")),
      ("Status", Option("status")),
      (if (isMobile || shortLabel) "Sch" else "Scheduled", None),
      (if (isMobile || shortLabel) "Exp" else "Expected", None),
      ("Exp PCP", Option("arrivals__table__flight-est-pcp")),
      ("Est PCP Pax", Option("arrivals__table__flight__pcp-pax__header")))
  }

  private def gateOrStandTh = {
    <.span(
      Tippy.info(
        "Select any gate / stand below to see the walk time. If it's not correct, contact us and we'll change it for you."
      )
    )
  }
}

//case class Country(name: String, twoLetterCode: String, threeLetterCode: String, id: Int)
//
//object CountryOptions {
//  def countries: Seq[Country] = Seq(
//    Country(twoLetterCode = "AF", threeLetterCode = "AFG", name = "Afghanistan", id = 4),
//    Country(twoLetterCode = "AL", threeLetterCode = "ALB", name = "Albania", id = 8),
//    Country(twoLetterCode = "DZ", threeLetterCode = "DZA", name = "Algeria", id = 12),
//    Country(twoLetterCode = "AS", threeLetterCode = "ASM", name = "American Samoa", id = 16),
//    Country(twoLetterCode = "AD", threeLetterCode = "AND", name = "Andorra", id = 20),
//    Country(twoLetterCode = "AO", threeLetterCode = "AGO", name = "Angola", id = 24),
//    Country(twoLetterCode = "AI", threeLetterCode = "AIA", name = "Anguilla", id = 660),
//    Country(twoLetterCode = "AQ", threeLetterCode = "ATA", name = "Antarctica", id = 10),
//    Country(twoLetterCode = "AG", threeLetterCode = "ATG", name = "Antigua and Barbuda", id = 28),
//    Country(twoLetterCode = "AR", threeLetterCode = "ARG", name = "Argentina", id = 32),
//    Country(twoLetterCode = "AM", threeLetterCode = "ARM", name = "Armenia", id = 51),
//    Country(twoLetterCode = "AW", threeLetterCode = "ABW", name = "Aruba", id = 533),
//    Country(twoLetterCode = "AU", threeLetterCode = "AUS", name = "Australia", id = 36),
//    Country(twoLetterCode = "AT", threeLetterCode = "AUT", name = "Austria", id = 40),
//    Country(twoLetterCode = "AZ", threeLetterCode = "AZE", name = "Azerbaijan", id = 31),
//    Country(twoLetterCode = "BS", threeLetterCode = "BHS", name = "Bahamas (the)", id = 44),
//    Country(twoLetterCode = "BH", threeLetterCode = "BHR", name = "Bahrain", id = 48),
//    Country(twoLetterCode = "BD", threeLetterCode = "BGD", name = "Bangladesh", id = 50),
//    Country(twoLetterCode = "BB", threeLetterCode = "BRB", name = "Barbados", id = 52),
//    Country(twoLetterCode = "BY", threeLetterCode = "BLR", name = "Belarus", id = 112),
//    Country(twoLetterCode = "BE", threeLetterCode = "BEL", name = "Belgium", id = 56),
//    Country(twoLetterCode = "BZ", threeLetterCode = "BLZ", name = "Belize", id = 84),
//    Country(twoLetterCode = "BJ", threeLetterCode = "BEN", name = "Benin", id = 204),
//    Country(twoLetterCode = "BM", threeLetterCode = "BMU", name = "Bermuda", id = 60),
//    Country(twoLetterCode = "BT", threeLetterCode = "BTN", name = "Bhutan", id = 64),
//    Country(twoLetterCode = "BO", threeLetterCode = "BOL", name = "Bolivia (Plurinational State of)", id = 68),
//    Country(twoLetterCode = "BQ", threeLetterCode = "BES", name = "Bonaire, Sint Eustatius and Saba", id = 535),
//    Country(twoLetterCode = "BA", threeLetterCode = "BIH", name = "Bosnia and Herzegovina", id = 70),
//    Country(twoLetterCode = "BW", threeLetterCode = "BWA", name = "Botswana", id = 72),
//    Country(twoLetterCode = "BV", threeLetterCode = "BVT", name = "Bouvet Island", id = 74),
//    Country(twoLetterCode = "BR", threeLetterCode = "BRA", name = "Brazil", id = 76),
//    Country(twoLetterCode = "IO", threeLetterCode = "IOT", name = "British Indian Ocean Territory (the)", id = 86),
//    Country(twoLetterCode = "BN", threeLetterCode = "BRN", name = "Brunei Darussalam", id = 96),
//    Country(twoLetterCode = "BG", threeLetterCode = "BGR", name = "Bulgaria", id = 100),
//    Country(twoLetterCode = "BF", threeLetterCode = "BFA", name = "Burkina Faso", id = 854),
//    Country(twoLetterCode = "BI", threeLetterCode = "BDI", name = "Burundi", id = 108),
//    Country(twoLetterCode = "CV", threeLetterCode = "CPV", name = "Cabo Verde", id = 132),
//    Country(twoLetterCode = "KH", threeLetterCode = "KHM", name = "Cambodia", id = 116),
//    Country(twoLetterCode = "CM", threeLetterCode = "CMR", name = "Cameroon", id = 120),
//    Country(twoLetterCode = "CA", threeLetterCode = "CAN", name = "Canada", id = 124),
//    Country(twoLetterCode = "KY", threeLetterCode = "CYM", name = "Cayman Islands (the)", id = 136),
//    Country(twoLetterCode = "CF", threeLetterCode = "CAF", name = "Central African Republic (the)", id = 140),
//    Country(twoLetterCode = "TD", threeLetterCode = "TCD", name = "Chad", id = 148),
//    Country(twoLetterCode = "CL", threeLetterCode = "CHL", name = "Chile", id = 152),
//    Country(twoLetterCode = "CN", threeLetterCode = "CHN", name = "China", id = 156),
//    Country(twoLetterCode = "CX", threeLetterCode = "CXR", name = "Christmas Island", id = 162),
//    Country(twoLetterCode = "CC", threeLetterCode = "CCK", name = "Cocos (Keeling) Islands (the)", id = 166),
//    Country(twoLetterCode = "CO", threeLetterCode = "COL", name = "Colombia", id = 170),
//    Country(twoLetterCode = "KM", threeLetterCode = "COM", name = "Comoros (the)", id = 174),
//    Country(twoLetterCode = "CD", threeLetterCode = "COD", name = "Congo (the Democratic Republic of the)", id = 180),
//    Country(twoLetterCode = "CG", threeLetterCode = "COG", name = "Congo (the)", id = 178),
//    Country(twoLetterCode = "CK", threeLetterCode = "COK", name = "Cook Islands (the)", id = 184),
//    Country(twoLetterCode = "CR", threeLetterCode = "CRI", name = "Costa Rica", id = 188),
//    Country(twoLetterCode = "HR", threeLetterCode = "HRV", name = "Croatia", id = 191),
//    Country(twoLetterCode = "CU", threeLetterCode = "CUB", name = "Cuba", id = 192),
//    Country(twoLetterCode = "CW", threeLetterCode = "CUW", name = "Curaçao", id = 531),
//    Country(twoLetterCode = "CY", threeLetterCode = "CYP", name = "Cyprus", id = 196),
//    Country(twoLetterCode = "CZ", threeLetterCode = "CZE", name = "Czechia", id = 203),
//    Country(twoLetterCode = "CI", threeLetterCode = "CIV", name = "Côte d'Ivoire", id = 384),
//    Country(twoLetterCode = "DK", threeLetterCode = "DNK", name = "Denmark", id = 208),
//    Country(twoLetterCode = "DJ", threeLetterCode = "DJI", name = "Djibouti", id = 262),
//    Country(twoLetterCode = "DM", threeLetterCode = "DMA", name = "Dominica", id = 212),
//    Country(twoLetterCode = "DO", threeLetterCode = "DOM", name = "Dominican Republic (the)", id = 214),
//    Country(twoLetterCode = "EC", threeLetterCode = "ECU", name = "Ecuador", id = 218),
//    Country(twoLetterCode = "EG", threeLetterCode = "EGY", name = "Egypt", id = 818),
//    Country(twoLetterCode = "SV", threeLetterCode = "SLV", name = "El Salvador", id = 222),
//    Country(twoLetterCode = "GQ", threeLetterCode = "GNQ", name = "Equatorial Guinea", id = 226),
//    Country(twoLetterCode = "ER", threeLetterCode = "ERI", name = "Eritrea", id = 232),
//    Country(twoLetterCode = "EE", threeLetterCode = "EST", name = "Estonia", id = 233),
//    Country(twoLetterCode = "SZ", threeLetterCode = "SWZ", name = "Eswatini", id = 748),
//    Country(twoLetterCode = "ET", threeLetterCode = "ETH", name = "Ethiopia", id = 231),
//    Country(twoLetterCode = "FK", threeLetterCode = "FLK", name = "Falkland Islands (the) [Malvinas]", id = 238),
//    Country(twoLetterCode = "FO", threeLetterCode = "FRO", name = "Faroe Islands (the)", id = 234),
//    Country(twoLetterCode = "FJ", threeLetterCode = "FJI", name = "Fiji", id = 242),
//    Country(twoLetterCode = "FI", threeLetterCode = "FIN", name = "Finland", id = 246),
//    Country(twoLetterCode = "FR", threeLetterCode = "FRA", name = "France", id = 250),
//    Country(twoLetterCode = "GF", threeLetterCode = "GUF", name = "French Guiana", id = 254),
//    Country(twoLetterCode = "PF", threeLetterCode = "PYF", name = "French Polynesia", id = 258),
//    Country(twoLetterCode = "TF", threeLetterCode = "ATF", name = "French Southern Territories (the)", id = 260),
//    Country(twoLetterCode = "GA", threeLetterCode = "GAB", name = "Gabon", id = 266),
//    Country(twoLetterCode = "GM", threeLetterCode = "GMB", name = "Gambia (the)", id = 270),
//    Country(twoLetterCode = "GE", threeLetterCode = "GEO", name = "Georgia", id = 268),
//    Country(twoLetterCode = "DE", threeLetterCode = "DEU", name = "Germany", id = 276),
//    Country(twoLetterCode = "GH", threeLetterCode = "GHA", name = "Ghana", id = 288),
//    Country(twoLetterCode = "GI", threeLetterCode = "GIB", name = "Gibraltar", id = 292),
//    Country(twoLetterCode = "GR", threeLetterCode = "GRC", name = "Greece", id = 300),
//    Country(twoLetterCode = "GL", threeLetterCode = "GRL", name = "Greenland", id = 304),
//    Country(twoLetterCode = "GD", threeLetterCode = "GRD", name = "Grenada", id = 308),
//    Country(twoLetterCode = "GP", threeLetterCode = "GLP", name = "Guadeloupe", id = 312),
//    Country(twoLetterCode = "GU", threeLetterCode = "GUM", name = "Guam", id = 316),
//    Country(twoLetterCode = "GT", threeLetterCode = "GTM", name = "Guatemala", id = 320),
//    Country(twoLetterCode = "GG", threeLetterCode = "GGY", name = "Guernsey", id = 831),
//    Country(twoLetterCode = "GN", threeLetterCode = "GIN", name = "Guinea", id = 324),
//    Country(twoLetterCode = "GW", threeLetterCode = "GNB", name = "Guinea-Bissau", id = 624),
//    Country(twoLetterCode = "GY", threeLetterCode = "GUY", name = "Guyana", id = 328),
//    Country(twoLetterCode = "HT", threeLetterCode = "HTI", name = "Haiti", id = 332),
//    Country(twoLetterCode = "HM", threeLetterCode = "HMD", name = "Heard Island and McDonald Islands", id = 334),
//    Country(twoLetterCode = "VA", threeLetterCode = "VAT", name = "Holy See (the)", id = 336),
//    Country(twoLetterCode = "HN", threeLetterCode = "HND", name = "Honduras", id = 340),
//    Country(twoLetterCode = "HK", threeLetterCode = "HKG", name = "Hong Kong", id = 344),
//    Country(twoLetterCode = "HU", threeLetterCode = "HUN", name = "Hungary", id = 348),
//    Country(twoLetterCode = "IS", threeLetterCode = "ISL", name = "Iceland", id = 352),
//    Country(twoLetterCode = "IN", threeLetterCode = "IND", name = "India", id = 356),
//    Country(twoLetterCode = "ID", threeLetterCode = "IDN", name = "Indonesia", id = 360),
//    Country(twoLetterCode = "IR", threeLetterCode = "IRN", name = "Iran (Islamic Republic of)", id = 364),
//    Country(twoLetterCode = "IQ", threeLetterCode = "IRQ", name = "Iraq", id = 368),
//    Country(twoLetterCode = "IE", threeLetterCode = "IRL", name = "Ireland", id = 372),
//    Country(twoLetterCode = "IM", threeLetterCode = "IMN", name = "Isle of Man", id = 833),
//    Country(twoLetterCode = "IL", threeLetterCode = "ISR", name = "Israel", id = 376),
//    Country(twoLetterCode = "IT", threeLetterCode = "ITA", name = "Italy", id = 380),
//    Country(twoLetterCode = "JM", threeLetterCode = "JAM", name = "Jamaica", id = 388),
//    Country(twoLetterCode = "JP", threeLetterCode = "JPN", name = "Japan", id = 392),
//    Country(twoLetterCode = "JE", threeLetterCode = "JEY", name = "Jersey", id = 832),
//    Country(twoLetterCode = "JO", threeLetterCode = "JOR", name = "Jordan", id = 400),
//    Country(twoLetterCode = "KZ", threeLetterCode = "KAZ", name = "Kazakhstan", id = 398),
//    Country(twoLetterCode = "KE", threeLetterCode = "KEN", name = "Kenya", id = 404),
//    Country(twoLetterCode = "KI", threeLetterCode = "KIR", name = "Kiribati", id = 296),
//    Country(twoLetterCode = "KP", threeLetterCode = "PRK", name = "Korea (the Democratic People's Republic of)", id = 408),
//    Country(twoLetterCode = "KR", threeLetterCode = "KOR", name = "Korea (the Republic of)", id = 410),
//    Country(twoLetterCode = "KW", threeLetterCode = "KWT", name = "Kuwait", id = 414),
//    Country(twoLetterCode = "KG", threeLetterCode = "KGZ", name = "Kyrgyzstan", id = 417),
//    Country(twoLetterCode = "LA", threeLetterCode = "LAO", name = "Lao People's Democratic Republic (the)", id = 418),
//    Country(twoLetterCode = "LV", threeLetterCode = "LVA", name = "Latvia", id = 428),
//    Country(twoLetterCode = "LB", threeLetterCode = "LBN", name = "Lebanon", id = 422),
//    Country(twoLetterCode = "LS", threeLetterCode = "LSO", name = "Lesotho", id = 426),
//    Country(twoLetterCode = "LR", threeLetterCode = "LBR", name = "Liberia", id = 430),
//    Country(twoLetterCode = "LY", threeLetterCode = "LBY", name = "Libya", id = 434),
//    Country(twoLetterCode = "LI", threeLetterCode = "LIE", name = "Liechtenstein", id = 438),
//    Country(twoLetterCode = "LT", threeLetterCode = "LTU", name = "Lithuania", id = 440),
//    Country(twoLetterCode = "LU", threeLetterCode = "LUX", name = "Luxembourg", id = 442),
//    Country(twoLetterCode = "MO", threeLetterCode = "MAC", name = "Macao", id = 446),
//    Country(twoLetterCode = "MG", threeLetterCode = "MDG", name = "Madagascar", id = 450),
//    Country(twoLetterCode = "MW", threeLetterCode = "MWI", name = "Malawi", id = 454),
//    Country(twoLetterCode = "MY", threeLetterCode = "MYS", name = "Malaysia", id = 458),
//    Country(twoLetterCode = "MV", threeLetterCode = "MDV", name = "Maldives", id = 462),
//    Country(twoLetterCode = "ML", threeLetterCode = "MLI", name = "Mali", id = 466),
//    Country(twoLetterCode = "MT", threeLetterCode = "MLT", name = "Malta", id = 470),
//    Country(twoLetterCode = "MH", threeLetterCode = "MHL", name = "Marshall Islands (the)", id = 584),
//    Country(twoLetterCode = "MQ", threeLetterCode = "MTQ", name = "Martinique", id = 474),
//    Country(twoLetterCode = "MR", threeLetterCode = "MRT", name = "Mauritania", id = 478),
//    Country(twoLetterCode = "MU", threeLetterCode = "MUS", name = "Mauritius", id = 480),
//    Country(twoLetterCode = "YT", threeLetterCode = "MYT", name = "Mayotte", id = 175),
//    Country(twoLetterCode = "MX", threeLetterCode = "MEX", name = "Mexico", id = 484),
//    Country(twoLetterCode = "FM", threeLetterCode = "FSM", name = "Micronesia (Federated States of)", id = 583),
//    Country(twoLetterCode = "MD", threeLetterCode = "MDA", name = "Moldova (the Republic of)", id = 498),
//    Country(twoLetterCode = "MC", threeLetterCode = "MCO", name = "Monaco", id = 492),
//    Country(twoLetterCode = "MN", threeLetterCode = "MNG", name = "Mongolia", id = 496),
//    Country(twoLetterCode = "ME", threeLetterCode = "MNE", name = "Montenegro", id = 499),
//    Country(twoLetterCode = "MS", threeLetterCode = "MSR", name = "Montserrat", id = 500),
//    Country(twoLetterCode = "MA", threeLetterCode = "MAR", name = "Morocco", id = 504),
//    Country(twoLetterCode = "MZ", threeLetterCode = "MOZ", name = "Mozambique", id = 508),
//    Country(twoLetterCode = "MM", threeLetterCode = "MMR", name = "Myanmar", id = 104),
//    Country(twoLetterCode = "NA", threeLetterCode = "NAM", name = "Namibia", id = 516),
//    Country(twoLetterCode = "NR", threeLetterCode = "NRU", name = "Nauru", id = 520),
//    Country(twoLetterCode = "NP", threeLetterCode = "NPL", name = "Nepal", id = 524),
//    Country(twoLetterCode = "NL", threeLetterCode = "NLD", name = "Netherlands (Kingdom of the)", id = 528),
//    Country(twoLetterCode = "NC", threeLetterCode = "NCL", name = "New Caledonia", id = 540),
//    Country(twoLetterCode = "NZ", threeLetterCode = "NZL", name = "New Zealand", id = 554),
//    Country(twoLetterCode = "NI", threeLetterCode = "NIC", name = "Nicaragua", id = 558),
//    Country(twoLetterCode = "NE", threeLetterCode = "NER", name = "Niger (the)", id = 562),
//    Country(twoLetterCode = "NG", threeLetterCode = "NGA", name = "Nigeria", id = 566),
//    Country(twoLetterCode = "NU", threeLetterCode = "NIU", name = "Niue", id = 570),
//    Country(twoLetterCode = "NF", threeLetterCode = "NFK", name = "Norfolk Island", id = 574),
//    Country(twoLetterCode = "MK", threeLetterCode = "MKD", name = "North Macedonia", id = 807),
//    Country(twoLetterCode = "MP", threeLetterCode = "MNP", name = "Northern Mariana Islands (the)", id = 580),
//    Country(twoLetterCode = "NO", threeLetterCode = "NOR", name = "Norway", id = 578),
//    Country(twoLetterCode = "OM", threeLetterCode = "OMN", name = "Oman", id = 512),
//    Country(twoLetterCode = "PK", threeLetterCode = "PAK", name = "Pakistan", id = 586),
//    Country(twoLetterCode = "PW", threeLetterCode = "PLW", name = "Palau", id = 585),
//    Country(twoLetterCode = "PS", threeLetterCode = "PSE", name = "Palestine, State of", id = 275),
//    Country(twoLetterCode = "PA", threeLetterCode = "PAN", name = "Panama", id = 591),
//    Country(twoLetterCode = "PG", threeLetterCode = "PNG", name = "Papua New Guinea", id = 598),
//    Country(twoLetterCode = "PY", threeLetterCode = "PRY", name = "Paraguay", id = 600),
//    Country(twoLetterCode = "PE", threeLetterCode = "PER", name = "Peru", id = 604),
//    Country(twoLetterCode = "PH", threeLetterCode = "PHL", name = "Philippines (the)", id = 608),
//    Country(twoLetterCode = "PN", threeLetterCode = "PCN", name = "Pitcairn", id = 612),
//    Country(twoLetterCode = "PL", threeLetterCode = "POL", name = "Poland", id = 616),
//    Country(twoLetterCode = "PT", threeLetterCode = "PRT", name = "Portugal", id = 620),
//    Country(twoLetterCode = "PR", threeLetterCode = "PRI", name = "Puerto Rico", id = 630),
//    Country(twoLetterCode = "QA", threeLetterCode = "QAT", name = "Qatar", id = 634),
//    Country(twoLetterCode = "RO", threeLetterCode = "ROU", name = "Romania", id = 642),
//    Country(twoLetterCode = "RU", threeLetterCode = "RUS", name = "Russian Federation (the)", id = 643),
//    Country(twoLetterCode = "RW", threeLetterCode = "RWA", name = "Rwanda", id = 646),
//    Country(twoLetterCode = "RE", threeLetterCode = "REU", name = "Réunion", id = 638),
//    Country(twoLetterCode = "BL", threeLetterCode = "BLM", name = "Saint Barthélemy", id = 652),
//    Country(twoLetterCode = "SH", threeLetterCode = "SHN", name = "Saint Helena, Ascension and Tristan da Cunha", id = 654),
//    Country(twoLetterCode = "KN", threeLetterCode = "KNA", name = "Saint Kitts and Nevis", id = 659),
//    Country(twoLetterCode = "LC", threeLetterCode = "LCA", name = "Saint Lucia", id = 662),
//    Country(twoLetterCode = "MF", threeLetterCode = "MAF", name = "Saint Martin (French part)", id = 663),
//    Country(twoLetterCode = "PM", threeLetterCode = "SPM", name = "Saint Pierre and Miquelon", id = 666),
//    Country(twoLetterCode = "VC", threeLetterCode = "VCT", name = "Saint Vincent and the Grenadines", id = 670),
//    Country(twoLetterCode = "WS", threeLetterCode = "WSM", name = "Samoa", id = 882),
//    Country(twoLetterCode = "SM", threeLetterCode = "SMR", name = "San Marino", id = 674),
//    Country(twoLetterCode = "ST", threeLetterCode = "STP", name = "Sao Tome and Principe", id = 678),
//    Country(twoLetterCode = "SA", threeLetterCode = "SAU", name = "Saudi Arabia", id = 682),
//    Country(twoLetterCode = "SN", threeLetterCode = "SEN", name = "Senegal", id = 686),
//    Country(twoLetterCode = "RS", threeLetterCode = "SRB", name = "Serbia", id = 688),
//    Country(twoLetterCode = "SC", threeLetterCode = "SYC", name = "Seychelles", id = 690),
//    Country(twoLetterCode = "SL", threeLetterCode = "SLE", name = "Sierra Leone", id = 694),
//    Country(twoLetterCode = "SG", threeLetterCode = "SGP", name = "Singapore", id = 702),
//    Country(twoLetterCode = "SX", threeLetterCode = "SXM", name = "Sint Maarten (Dutch part)", id = 534),
//    Country(twoLetterCode = "SK", threeLetterCode = "SVK", name = "Slovakia", id = 703),
//    Country(twoLetterCode = "SI", threeLetterCode = "SVN", name = "Slovenia", id = 705),
//    Country(twoLetterCode = "SB", threeLetterCode = "SLB", name = "Solomon Islands", id = 90),
//    Country(twoLetterCode = "SO", threeLetterCode = "SOM", name = "Somalia", id = 706),
//    Country(twoLetterCode = "ZA", threeLetterCode = "ZAF", name = "South Africa", id = 710),
//    Country(twoLetterCode = "GS", threeLetterCode = "SGS", name = "South Georgia and the South Sandwich Islands", id = 239),
//    Country(twoLetterCode = "SS", threeLetterCode = "SSD", name = "South Sudan", id = 728),
//    Country(twoLetterCode = "ES", threeLetterCode = "ESP", name = "Spain", id = 724),
//    Country(twoLetterCode = "LK", threeLetterCode = "LKA", name = "Sri Lanka", id = 144),
//    Country(twoLetterCode = "SD", threeLetterCode = "SDN", name = "Sudan (the)", id = 729),
//    Country(twoLetterCode = "SR", threeLetterCode = "SUR", name = "Suriname", id = 740),
//    Country(twoLetterCode = "SJ", threeLetterCode = "SJM", name = "Svalbard and Jan Mayen", id = 744),
//    Country(twoLetterCode = "SE", threeLetterCode = "SWE", name = "Sweden", id = 752),
//    Country(twoLetterCode = "CH", threeLetterCode = "CHE", name = "Switzerland", id = 756),
//    Country(twoLetterCode = "SY", threeLetterCode = "SYR", name = "Syrian Arab Republic (the)", id = 760),
//    Country(twoLetterCode = "TW", threeLetterCode = "TWN", name = "Taiwan (Province of China)", id = 158),
//    Country(twoLetterCode = "TJ", threeLetterCode = "TJK", name = "Tajikistan", id = 762),
//    Country(twoLetterCode = "TZ", threeLetterCode = "TZA", name = "Tanzania, the United Republic of", id = 834),
//    Country(twoLetterCode = "TH", threeLetterCode = "THA", name = "Thailand", id = 764),
//    Country(twoLetterCode = "TL", threeLetterCode = "TLS", name = "Timor-Leste", id = 626),
//    Country(twoLetterCode = "TG", threeLetterCode = "TGO", name = "Togo", id = 768),
//    Country(twoLetterCode = "TK", threeLetterCode = "TKL", name = "Tokelau", id = 772),
//    Country(twoLetterCode = "TO", threeLetterCode = "TON", name = "Tonga", id = 776),
//    Country(twoLetterCode = "TT", threeLetterCode = "TTO", name = "Trinidad and Tobago", id = 780),
//    Country(twoLetterCode = "TN", threeLetterCode = "TUN", name = "Tunisia", id = 788),
//    Country(twoLetterCode = "TM", threeLetterCode = "TKM", name = "Turkmenistan", id = 795),
//    Country(twoLetterCode = "TC", threeLetterCode = "TCA", name = "Turks and Caicos Islands (the)", id = 796),
//    Country(twoLetterCode = "TV", threeLetterCode = "TUV", name = "Tuvalu", id = 798),
//    Country(twoLetterCode = "TR", threeLetterCode = "TUR", name = "Türkiye", id = 792),
//    Country(twoLetterCode = "UG", threeLetterCode = "UGA", name = "Uganda", id = 800),
//    Country(twoLetterCode = "UA", threeLetterCode = "UKR", name = "Ukraine", id = 804),
//    Country(twoLetterCode = "AE", threeLetterCode = "ARE", name = "United Arab Emirates (the)", id = 784),
//    Country(twoLetterCode = "GB", threeLetterCode = "GBR", name = "United Kingdom of Great Britain and Northern Ireland (the)", id = 826),
//    Country(twoLetterCode = "UM", threeLetterCode = "UMI", name = "United States Minor Outlying Islands (the)", id = 581),
//    Country(twoLetterCode = "US", threeLetterCode = "USA", name = "United States of America (the)", id = 840),
//    Country(twoLetterCode = "UY", threeLetterCode = "URY", name = "Uruguay", id = 858),
//    Country(twoLetterCode = "UZ", threeLetterCode = "UZB", name = "Uzbekistan", id = 860),
//    Country(twoLetterCode = "VU", threeLetterCode = "VUT", name = "Vanuatu", id = 548),
//    Country(twoLetterCode = "VE", threeLetterCode = "VEN", name = "Venezuela (Bolivarian Republic of)", id = 862),
//    Country(twoLetterCode = "VN", threeLetterCode = "VNM", name = "Viet Nam", id = 704),
//    Country(twoLetterCode = "VG", threeLetterCode = "VGB", name = "Virgin Islands (British)", id = 92),
//    Country(twoLetterCode = "VI", threeLetterCode = "VIR", name = "Virgin Islands (U.S.)", id = 850),
//    Country(twoLetterCode = "WF", threeLetterCode = "WLF", name = "Wallis and Futuna", id = 876),
//    Country(twoLetterCode = "EH", threeLetterCode = "ESH", name = "Western Sahara*", id = 732),
//    Country(twoLetterCode = "YE", threeLetterCode = "YEM", name = "Yemen", id = 887),
//    Country(twoLetterCode = "ZM", threeLetterCode = "ZMB", name = "Zambia", id = 894),
//    Country(twoLetterCode = "ZW", threeLetterCode = "ZWE", name = "Zimbabwe", id = 716),
//    Country(twoLetterCode = "AX", threeLetterCode = "ALA", name = "Åland Islands", id = 248),
//    Country(twoLetterCode = "", threeLetterCode = "EUE", name = "European Union laissez-passer", id = -1),
//    Country(twoLetterCode = "", threeLetterCode = "XOM", name = "Sovereign Military Order of Malta", id = -2),
//    Country(twoLetterCode = "", threeLetterCode = "XPO", name = "Interpol travel documents", id = -3),
//    Country(twoLetterCode = "", threeLetterCode = "XXA", name = "Stateless person", id = -4),
//    Country(twoLetterCode = "", threeLetterCode = "XXB", name = "Refugee (Article 1)", id = -5),
//    Country(twoLetterCode = "", threeLetterCode = "XXC", name = "Refugee (non-Article 1)", id = -6),
//    Country(twoLetterCode = "", threeLetterCode = "XXX", name = "Unspecified nationality", id = -7),
//    Country(twoLetterCode = "", threeLetterCode = "WSA", name = "World Service Authority World Passport", id = -8),
//  )
//}
