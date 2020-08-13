package drt.client.components

import diode.data.Pot
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.AirportConfig
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{VdomTagOf, _}
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom.html
import org.scalajs.dom.html.Div

sealed trait FaqsPage

object AllFaqsPage extends FaqsPage {

  case class Props(showSection: String)

  val desksAndQueuesDetails = FaqDetails("Desk and Queues", "#faqs/DeskAndQueues", List(
    <.p(<.strong(s"Q. What does the ‘+’ and ‘-‘ buttons do under the available icon on the desks and queues tab?")),
    <.p(s"- The buttons allows you to add or subtract the amount of available staff for duties such as casework"),
    <.p(<.strong(s"Q. How are staff allocated if I select the ‘Available staff deployment’ radio button?")),
    <.p(s"- Deploys staff you require not to breach an SLA."),
    <.p(<.strong(s"Q. How are staff allocated if I select the ‘Recommendations’ radio button?")),
    <.p(s"- Deploys how many staff are required if staffing wasn’t an issue."),
    <.p(<.strong(s"Q. What does the Misc column mean?")),
    <.p(s"- Fixed points"),
    <.p(<.strong(s"Q. Why does the recommended column in the PCP section go red or amber?")),
    <.p(s"-	It goes amber when the amount of staff that are available is close to the recommended staffing for that."),
    <.p(<.strong(s"Q. Why does the est wait time go red or amber in the desk and queue tab?")),
    <.p(s"- It goes amber because the wait time is close to the SLA, it would then go red if the wait time goes above the SLA.")
  ))

  val arrivalDetails = FaqDetails("Arrivals", "#faqs/arrivals", List(
    <.p(<.strong(s"Q. What do the RAG colours mean for each flight?")),
    <.p(s"- Green means there is API data for the flight, Amber means historic data is being used from the last 12 weeks, Red means"),
    <.p(<.strong(s"Q. Will I see API data for all flights ?")),
    <.p(s"- "),
    <.p(<.strong(s"Q. What is the difference between the flights that are highlighted white, blue or red?")),
    <.p(s"- White - have arrived at the immigration hall, Red- the flight is either an hour early or an hour late, Blue- flight has not arrived in the immigration hall as yet."),
    <.p(<.strong(s"Q. Why does the status of some flights show UNK?")),
    <.p(s"-")
  ))

  val portConfigurationDetails = FaqDetails("Port Configuration", "#faqs/portConfiguration", List(
    <.p(<.strong(s"Q.What are the processing times for each split ?")),
    <.p(s"- Processing times are unique to each port."),
    <.p(<.strong(s"Q. How do you estimate walk times ?")),
    <.p(s"- Walk times have been manually measured, where they haven’t a default processing time will be set."),
    <.p(<.strong(s"Q. What information does each of the feed statuses provide ?")),
    <.p(s"-")
  ))

  val staffMovementDetails = FaqDetails("Staff Movements", "#faqs/staff-movements", List(
    <.p(<.strong(s"Q.How do I can add fixed points/ Misc staff?")),
    <.p(s"- Under the staff movements tab there will be a section on the left that states ‘Miscellaneous Staff’.  In that section you can add or remove staff. Snapshot view"),
    <.p(<.strong(s"Q. What is the ‘snapshot’ tab?")),
    <.p(s"- You can look at information from dates that have passed."),
    <.p(<.strong(s"Q. What is the difference between current and snapshot view?")),
    <.p(s"- The current tab allows you to look at what is currently happening, whilst the snapshot tab allows you to look back into the past at what has happened."),
  ))

  val monthlyStaffingDetails = FaqDetails("Monthly Staffing", "#faqs/monthly-staffing", List(
    <.p(<.strong(s"Q. How do I add my staff to the tool?")),
    <.p(s"- If you have access to the feature you should see a tab called ‘monthly staffing’ once you click on your terminal."),
  ))

  val faqClassName = ^.className := "faqs-class"

  val faqHeaderName = <.h3("FAQs")

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FAQs")
    .render_P(props =>
      props.showSection match {
        case "DeskAndQueues" => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.faqComponent,
          arrivalDetails.divComponent,
          portConfigurationDetails.divComponent,
          staffMovementDetails.divComponent,
          monthlyStaffingDetails.divComponent)
        case "arrivals" => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.divComponent,
          arrivalDetails.faqComponent,
          portConfigurationDetails.divComponent,
          staffMovementDetails.divComponent,
          monthlyStaffingDetails.divComponent)
        case "portConfiguration" => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.divComponent,
          arrivalDetails.divComponent,
          portConfigurationDetails.faqComponent,
          staffMovementDetails.divComponent,
          monthlyStaffingDetails.divComponent)
        case "staff-movements" => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.divComponent,
          arrivalDetails.divComponent,
          portConfigurationDetails.divComponent,
          staffMovementDetails.faqComponent,
          monthlyStaffingDetails.divComponent)
        case "monthly-staffing" => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.divComponent,
          arrivalDetails.divComponent,
          portConfigurationDetails.divComponent,
          staffMovementDetails.divComponent,
          monthlyStaffingDetails.faqComponent)
        case _ => <.div(faqClassName, faqHeaderName,
          desksAndQueuesDetails.divComponent,
          arrivalDetails.divComponent,
          portConfigurationDetails.divComponent,
          staffMovementDetails.divComponent,
          monthlyStaffingDetails.divComponent)
      }
    )
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"faqs")
    })
    .build

  def apply(showSection: String): VdomElement = component(Props(showSection))
}


case class FaqDetails(title: String, href: String, faqsList: Seq[TagOf[html.Paragraph]]) {

  val faqComponent = FaqsComponent(s"- $title", faqsList)

  val divComponent: VdomTagOf[Div] = <.div(<.a(<.strong(s"+ $title"), ^.href := href))

}

object FaqsComponent extends FaqsPage {

  case class Props(header: String, faqsList: Seq[TagOf[html.Paragraph]])

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FAQs")
    .render_P { props =>
      val faqsRCP = SPACircuit.connect(m => FaqsModel(m.airportConfig))
      <.div(
        <.div(<.a(<.strong(s"${props.header}"), ^.href := "#faqs")),
        faqsRCP(faqsMP => {
          <.div(^.className := "faqs", faqsMP().airportConfig.renderReady(
            _ => {
              val deskAndQueuesFaqs = props.faqsList.toTagMod
              <.div(deskAndQueuesFaqs)
            }
          )
          )
        })
      )
    }
    .build

  def apply(header: String, faqsList: Seq[TagOf[html.Paragraph]]): VdomElement = component(Props(header, faqsList))

}


case class FaqsModel(airportConfig: Pot[AirportConfig])