package drt.client.components

import drt.client.actions.Actions._
import drt.client.components.ArrivalsExportComponent.StringExtended
import drt.client.services.SPACircuit
import io.kinoplan.scalajs.react.bridge.WithProps.toVdomNode
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.ExpandMore
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
          InputProps = params.InputProps,
          inputProps = params.inputProps,
          label = "Nationality or ICAO code".toVdom,
        )().rawNode
      }

      val options = js.Array(CountryOptions.countries.map { c =>
        MuiAutocompleteOption(label = s"${c.name} (${c.threeLetterCode})", id = c.id)
      }: _*)

      val flagCount = props.flaggedNationalities.size
      val flagCountDisplay = flagCount match {
        case 0 => ""
        case n => s" ($n)"
      }
      val clearFlags = flagCount match {
        case 0 => EmptyVdom
        case _ =>
          <.div(
            ^.style := js.Dictionary("display" -> "inline"),
            MuiButton(variant = MuiButton.Variant.text)(
              MuiTypography(variant = "body1", sx = SxProps(Map("textDecoration" -> "underline", "fontWeight" -> "bold")))(
                "Clear all"
              ),
              ^.onClick ==> { e => {
                e.preventDefault()
                Callback(SPACircuit.dispatch(ClearFlaggedNationalities))
              }
              }
            ),
          )
      }
      <.div(
        ^.style := js.Dictionary("display" -> "flex"),
        MuiAccordion(elevation = 0)(
          MuiAccordionSummary(expandIcon = toVdomNode(MuiIcons(ExpandMore)()))(
            <.div(^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center", "gap" -> "16px"),
              MuiTypography(variant = "body1", sx = SxProps(Map("textDecoration" -> "underline", "fontWeight" -> "bold")))(
                s"Flag flights $flagCountDisplay"
              ),
              clearFlags
            )
          ),
          MuiAccordionDetails(sx = SxProps(Map("mb" -> "16px")))(
            <.div(
              ^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center", "gap" -> "16px"),
              MuiAutocomplete[MuiAutocompleteOption](
                options = options,
                renderInput = acTextInput,
                sx = SxProps(Map("minWidth" -> "250px")),
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
              <.div(
                ^.style := js.Dictionary("display" -> "flex", "alignItems" -> "center", "gap" -> "16px"),
                props.flaggedNationalities.toList
                  .sortBy(_.name)
                  .map { c =>
                    val onDelete = (_: ReactEvent) => Callback(SPACircuit.dispatch(RemoveFlaggedNationality(c)))
                    MuiChip(label = s"${c.name} (${c.threeLetterCode})".toVdom, onDelete = onDelete)()
                  }
                  .toTagMod
              )
            )
          )
        )
      )
    }
    .configure(Reusability.shouldComponentUpdate)
    .build
}
