package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.AddUserToGroup
import drt.client.services._
import drt.shared.KeyCloakApi.KeyCloakUser
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

object ListKeyCloakUsers {

  case class Props()

  val component = ScalaComponent.builder[Props]("ListKeyCloakUsers")
    .renderP((scope, p) => {
      def approveUser(userId: String) = (_: ReactEventFromInput) =>
        Callback(SPACircuit.dispatch(AddUserToGroup(userId, "Approved")))


      val keyCloakUsers = SPACircuit.connect(m => m.keyCloakUsers)
      keyCloakUsers(usersMP => {
        val usersPot: Pot[List[KeyCloakUser]] = usersMP()
        <.div(
          usersPot.renderReady(users =>
           <.table(^.className := "key-cloak-users",
             users.map( user => <.tr(
               <.td(user.firstName),
               <.td(user.lastName),
               <.td(user.email),
               <.td(<.button("Approve", ^.className := "btn btn-primary", ^.onClick ==> approveUser(user.id)))
             )
           ).toTagMod)
          )
        )
      })
    }
    ).build

  def apply(): VdomElement = component(Props())
}
object KeyCloakUsersPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("ListKeyCloakUsers")
    .render_P(p => <.div(ListKeyCloakUsers())).build

  def apply(): VdomElement = component(Props())
}
