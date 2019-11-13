package drt.client.components

import java.util.UUID

import diode.data.Pot
import drt.client.SPAMain
import drt.client.SPAMain.{KeyCloakUserEditLoc, Loc}
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared.KeyCloakApi.KeyCloakUser
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}

object KeyCloakUsersPage {

  case class Props(router: RouterCtl[Loc])

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ListKeyCloakUsers")
    .renderP((scope, p) => {

      def editUser(userId: UUID) = (_: ReactEventFromInput) => p.router.set(KeyCloakUserEditLoc(userId))

      val keyCloakUsers = SPACircuit.connect(_.keyCloakUsers)
      keyCloakUsers(usersMP => {
        val usersPot: Pot[List[KeyCloakUser]] = usersMP()
        <.div(
          <.h2("DRT V2 Keycloak User List"),
          usersPot.renderReady(users =>
            <.div(
              <.div(^.className := "button-group", <.a("Export Users", ^.href := SPAMain.absoluteUrl("export/users"), ^.target := "_blank", ^.className := "btn btn-default")),
              <.table(^.className := "key-cloak-users",
                <.tbody(
                  users.map(user => <.tr(
                    <.td(user.firstName),
                    <.td(user.lastName),
                    <.td(user.email),
                    <.td(<.button("Edit", ^.className := "btn btn-primary", ^.onClick ==> editUser(user.id)))
                  )
                  ).toTagMod))
            ))
        )
      })
    }
    )
    .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("users")))
    .build

  def apply(router: RouterCtl[Loc]): VdomElement = component(Props(router))
}
