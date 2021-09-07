package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.SaveUserGroups
import drt.client.services._
import drt.shared.AirportConfigs
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

import java.util.UUID


object EditKeyCloakUser {

  case class Props(user: KeyCloakUser, groups: Set[String]) extends UseValueEq

  case class State(groups: Set[String]) extends UseValueEq

//  implicit val keycloakUser: Reusability[KeyCloakUser] = Reusability.derive[KeyCloakUser]
//  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
//  implicit val propsReuse: Reusability[Props] = Reusability.derive[Props]
  val component = ScalaComponent.builder[Props]("EditKeyCloakUser")
    .initialStateFromProps(p => State(p.groups))
    .renderPS((scope, props, state) => {

      val ports = AirportConfigs.portGroups

      val otherGroups = List(
        "Staff Admin"
      )

      def groupSelector(selectionGroups: List[String], selected: Set[String]) = selectionGroups.map(g =>
        <.div(^.className :="checkbox", <.label(<.input.checkbox(
          ^.value := g,
          ^.checked := selected.contains(g),
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val target = e.target
            scope.modState(s => {
              if (target.checked)
                s.copy(groups = s.groups + g)
              else
                s.copy(groups = s.groups.filterNot(_ == g))
            })
          })), s" $g"))
      ).toTagMod

      def updateGroups = {

        val groupsToAdd = scope.state.groups -- props.groups
        val groupsToRemove = props.groups -- scope.state.groups

        Callback {
          SPACircuit.dispatch(SaveUserGroups(props.user.id, groupsToAdd, groupsToRemove))
        }
      }

      <.div(^.className :="drt-form",
        <.div(^.className := "form-horizontal",
          <.div(^.className := "form-group",
            <.label(^.className := "col-sm-2 control-label", "User:"), <.div(^.className := "col-sm-3 form-control-static", props.user.email)),
          <.div(^.className := "form-group",
            <.label(^.className := "col-sm-2 control-label", "Ports:"), <.div(^.className := "col-sm-3", groupSelector(ports, state.groups))),
          <.div(^.className := "form-group",
            <.label(^.className := "col-sm-2 control-label", "Permission Level:"), <.div(^.className := "col-sm-3", groupSelector(otherGroups, state.groups))),
          <.div(^.className := "form-group",
            <.div(^.className := "col-sm-offset-2 col-sm-10",
            <.button(
              ^.className := "btn btn-primary",
              "Save",
              ^.onClick --> updateGroups)
            )
          )
        ))
    }
    )
//    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(user: KeyCloakUser, groups: Set[String]): VdomElement = component(Props(user, groups))
}

object EditKeyCloakUserPage {

  case class Props(userId: UUID) extends UseValueEq

  case class UsersAndGroups(usersPot: Pot[List[KeyCloakUser]], groupsPot: Pot[Set[KeyCloakGroup]]) extends UseValueEq

//  implicit val propsReuse: Reusability[Props] = Reusability.derive[Props]

  val component = ScalaComponent.builder[Props]("EditKeyCloakUserPage")
    .render_P(props => {

      val modelRCP = SPACircuit.connect(m => UsersAndGroups(m.keyCloakUsers, m.selectedUserGroups))
      modelRCP(modelMP => {
        val usersAndGroups = modelMP()

        <.div(
          <.h2(s"Edit Groups"),
          usersAndGroups.usersPot.renderReady(users =>
            usersAndGroups.groupsPot.renderReady(groups =>
              users.find(_.id == props.userId) match {
                case Some(user) =>
                  <.div(EditKeyCloakUser(user, groups.map(_.name)))
                case None => <.tbody(<.tr(<.td(s"Unable to find user with this id, please go back to the list users page.")))
              }
            )),
          usersAndGroups.groupsPot.renderPending(_ => <.tbody(<.tr(<.td(s"Updating groups..."))))
        )
      })
    }
    )
//    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(userId: UUID): VdomElement = component(Props(userId))
}
