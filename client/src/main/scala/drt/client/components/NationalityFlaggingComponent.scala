package drt.client.components

import drt.client.actions.Actions._
import drt.client.components.ArrivalsExportComponent.StringExtended
import drt.client.services.SPACircuit
import drt.shared.Country
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Flag
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.facade.React.Node
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, _}

import scala.scalajs.js


object NationalityFlaggingComponent {
  case class Props(flaggedNationalities: Set[Country])

  case class State(inputValue: String)

  implicit val propsReuse: Reusability[Props] = Reusability.derive[Props]
  implicit val stateReuse: Reusability[State] = Reusability.derive[State]

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("nationality-flagger")
    .initialState(State(""))
    .renderPS { (scope, props, state) =>
      val acTextInput: js.Function1[AutocompleteRenderInputParams, Node] = (params: AutocompleteRenderInputParams) => {
        MuiTextField(
          id = params.id,
          disabled = params.disabled,
          fullWidth = params.fullWidth,
          InputLabelProps = params.InputLabelProps,
          InputProps = js.Object.assign(
            params.InputProps,
            js.Dynamic.literal("startAdornment" -> MuiInputAdornment(position = "start")(MuiIcons(Flag)()).rawNode.asInstanceOf[js.Object]),
          ),
          inputProps = params.inputProps,
          label = "Enter nationalities or ICAO codes".toVdom,
        )().rawNode
      }

      val options = js.Array(CountryOptions.countries.map { c =>
        MuiAutocompleteOption(label = s"${c.name} (${c.threeLetterCode})", id = c.id)
      }: _*)

      val flagCount = props.flaggedNationalities.size

      val clearFlags = flagCount match {
        case 0 => EmptyVdom
        case _ =>
          <.div(^.style := js.Dictionary("padding-top" -> "22px"),
            MuiLink(variant = MuiButton.Variant.text, sx = SxProps(Map("minWidth" -> "40px")))(
              MuiTypography(variant = "body1", sx = SxProps(Map("fontWeight" -> "bold")))("Clear all"),
              ^.href := "#",
              ^.onClick ==> { e => {
                e.preventDefault()
                Callback(SPACircuit.dispatch(ClearFlaggedNationalities))
              }
              }))
      }
      <.div(^.style := js.Dictionary("padding-top" -> "0px"),
        <.div(^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center", "gap" -> "16px"),
          <.div(
            MuiTypography(sx = SxProps(Map("font-weight" -> "bold", "padding-bottom" -> "10px")))("Flag flights by pax nationality"),
            MuiAutocomplete[MuiAutocompleteOption](
              options = options,
              renderInput = acTextInput,
              sx = SxProps(Map("minWidth" -> "265px", "backgroundColor" -> "#FFFFFF")),
              getOptionLabel = (o: MuiAutocompleteOption) => o.label,
              isOptionEqualToValue = (o1: MuiAutocompleteOption, o2: MuiAutocompleteOption) => {
                o1.id == o2.id
              },
              inputValue = state.inputValue,
              onInputChange = (_: ReactEvent, value: String) => scope.modState(_.copy(inputValue = value)),
              value = null,
              onChange = (_: ReactEvent, value: MuiAutocompleteOption) => value match {
                case option: MuiAutocompleteOption =>
                  CountryOptions.countries.find(_.id == option.id) match {
                    case Some(c) =>
                      scope.modState(_.copy(inputValue = ""))
                        .map(_ => SPACircuit.dispatch(AddFlaggedNationality(c)))
                    case None => Callback.empty
                  }

                case _ => Callback.empty
              },
            )(),
          ), clearFlags
        ),
        <.div(^.style := js.Dictionary("padding-top" -> "16px", "display" -> "wrap", "alignItems" -> "center"),
          <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> "16px"),
            props.flaggedNationalities.toList
              .sortBy(_.name)
              .map { c =>
                val onDelete = (_: ReactEvent) => Callback(SPACircuit.dispatch(RemoveFlaggedNationality(c)))
                MuiChip(label = s"${c.name} (${c.threeLetterCode})".toVdom, onDelete = onDelete)()
              }
              .toTagMod)
        ))
    }
    .configure(Reusability.shouldComponentUpdate)
    .build
}
