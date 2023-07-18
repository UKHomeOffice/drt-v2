package drt.client.components

import diode.data.{Empty, Pot}
import diode.react.ReactConnectProxy
import drt.client.SPAMain.{ContactUsLoc, Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.client.services.handlers.{CloseFeatureGuideDialog, FeatureGuideDialog, GetFeatureGuides, GetViewedFeatureIds, IsNewFeatureAvailable}
import io.kinoplan.scalajs.react.material.ui.core.internal.Origin
import io.kinoplan.scalajs.react.material.ui.core.{MuiBadge, MuiSnackbar}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ScalaComponent}
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.training.FeatureGuide

case class NavbarModel(feedStatuses: Pot[Seq[FeedSourceStatuses]],
                       snackbarMessage: Pot[String],
                       featureGuides: Pot[Seq[FeatureGuide]],
                       showNewFeatureGuideOnLogin: Pot[Boolean],
                       featureGuideViewIds: Pot[Seq[String]])

object Navbar {
  case class Props(
                    ctl: RouterCtl[Loc],
                    page: Loc,
                    loggedInUser: LoggedInUser,
                    airportConfig: AirportConfig)

  case class State(showDropDown: Boolean, toggleDialog: Boolean)

  def handleClose: (ReactEvent, String) => Callback = (_, _) => {
    Callback(SPACircuit.dispatch(SetSnackbarMessage(Empty)))
  }

  class Backend($: BackendScope[Props, State]) {
    val rcp: ReactConnectProxy[NavbarModel] = SPACircuit
      .connect(m => NavbarModel(m.feedStatuses, m.snackbarMessage, m.featureGuides, m.showNewFeatureGuideOnLogin, m.featureGuideViewedIds))

    def handleOpenDialog(e: ReactEvent) = {
      e.preventDefaultCB >>
        $.modState(s => s.copy(toggleDialog = true))
    }

    def handleDialogClose(e: ReactEvent) = {
      e.preventDefaultCB >>
        $.modState(s => s.copy(toggleDialog = false)) >>
        Callback(SPACircuit.dispatch(CloseFeatureGuideDialog()))
    }

    def calculateBadgeCount(viewedFeatureIds: Seq[String], templateFeatureIds: Seq[String]): Int = {
      templateFeatureIds.count(id => !viewedFeatureIds.contains(id))
    }

    def componentDidMount() = {
      Callback(SPACircuit.dispatch(GetViewedFeatureIds())) >>
        Callback(SPACircuit.dispatch(GetFeatureGuides()))
    }

    def render(props: Props, state: State) = {
      def isLargeDisplay = dom.window.innerWidth > 800

      <.div(
        rcp { navbarModelProxy =>
          val navbarModel: NavbarModel = navbarModelProxy()
          val items = MainMenu.menuItems(props.airportConfig, props.page, props.loggedInUser.roles, Seq.empty)
          val currentItem = items.find { i =>
            (i.location, props.page) match {
              case (TerminalPageTabLoc(tn, _, _, _), TerminalPageTabLoc(tni, _, _, _)) => tn == tni
              case (current, itemLoc) => current == itemLoc
            }
          }

          <.div(^.className := "main-menu-wrapper",
            navbarModel.snackbarMessage.renderReady { message =>
              MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
                autoHideDuration = 5000,
                message = <.div(^.className := "muiSnackBar", message),
                open = true,
                onClose = handleClose)()
            },
            <.div(^.className := "main-menu-title",
              <.div(^.className := "main-menu-burger", Icon.bars),
              <.a(s"DRT ${props.airportConfig.portCode}"),
              currentItem.map(i => <.div(^.className := "main-menu-current-item", i.label)).getOrElse(""),
              ^.onClick --> {
                if (dom.window.innerWidth <= 768)
                  $.modState(s => s.copy(showDropDown = !s.showDropDown))
                else Callback.empty
              }),
            if (isLargeDisplay || state.showDropDown)
              <.div(^.className := "main-menu-content",
                MainMenu(props.ctl, props.page, navbarModel.feedStatuses.getOrElse(Seq()), props.airportConfig, props.loggedInUser),
                navbarModel.featureGuides.renderReady { trainingDataTemplates =>
                  navbarModel.featureGuideViewIds.renderReady { userFeatureViewCount =>
                    <.div(^.className := "main-menu-items",
                      <.div(if (trainingDataTemplates.nonEmpty) {
                        <.a(^.onClick ==> {
                          handleOpenDialog
                        }, MuiBadge(badgeContent = {
                          val badgeCount = calculateBadgeCount(userFeatureViewCount, trainingDataTemplates.map(_.id.map(_.toString).getOrElse("0")))
                          <.span(badgeCount)
                        }, showZero = true, color = "primary")("New Feature"))
                      } else EmptyVdom),
                      <.div(
                        navbarModel.featureGuides.renderReady { trainingDataTemplates =>
                          navbarModel.showNewFeatureGuideOnLogin.renderReady { showNewFeatureGuideOnLogin =>
                            FeatureGuideModalComponent(state.toggleDialog || showNewFeatureGuideOnLogin,
                              handleDialogClose,
                              trainingDataTemplates)
                          }
                        }
                      ),
                      <.div(^.className := "contact-us-link", props.ctl.link(ContactUsLoc)(Icon.envelope, " ", "Contact Us")),
                      <.div(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                        ^.onClick --> Callback(GoogleEventTracker.sendEvent(props.airportConfig.portCode.toString, "Log Out", props.loggedInUser.id))))
                    )
                  }
                })
            else EmptyVdom
          )
        }
      )
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("NavBar")
      .initialState(if (dom.window.innerWidth > 768) State(true, false) else State(false, false))
      .renderBackend[Backend]
      .componentDidMount(_.backend.componentDidMount())
      .build


  def apply(props: Props): Unmounted[Props, State, Backend] = component(props)

}
