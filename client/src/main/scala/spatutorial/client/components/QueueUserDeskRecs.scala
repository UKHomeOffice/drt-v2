package spatutorial.client.components

import diode.data.{Empty, Pot, Ready}
import diode.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import org.scalajs.dom.html
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components.DeskRecsTable.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._

import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined

object QueueUserDeskRecsComponent {

  case class Props(
                    terminalName: TerminalName,
                    queueName: QueueName,
                    userDeskRecsRowPotRCP: ReactConnectProxy[Pot[List[UserDeskRecsRow]]],
                    airportConfig: AirportConfig,
                    airportInfoPotsRCP: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    labelsPotRCP: ReactConnectProxy[Pot[IndexedSeq[String]]],
                    crunchResultPotRCP: ReactConnectProxy[Pot[CrunchResult]],
                    userDeskRecsPotRCP: ReactConnectProxy[Pot[UserDeskRecs]],
                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                    simulationResultPotRCP: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(
        ^.key := s"${props.terminalName}-${props.queueName}-QueueUserDeskRecs",
        currentUserDeskRecView(props)
      )
    ).build

  @ScalaJSDefined
  class Row(recommended_desks: String, wait_times_with_recommended: String,
            your_desks: String, wait_times_with_your_desks: String) extends js.Object {
  }

  def currentUserDeskRecView(props: Props): ReactTagOf[html.Div] = {
    <.div(
      ^.key := props.queueName,
      props.userDeskRecsRowPotRCP(userDeskRecsRosPotMP =>
        Panel(Panel.Props(s"Queue Simulation for ${props.terminalName} ${props.queueName}"),
          userDeskRecsRosPotMP().renderReady(userDeskRecsRows =>
            props.simulationResultPotRCP(simulationResultPotMP => {
              props.userDeskRecsPotRCP(
                userDeskRecsPotMP => UserDeskRecsComponent(props.terminalName, props.queueName, userDeskRecsRows,
                  props.airportConfig, props.airportInfoPotsRCP, props.flightsPotRCP, userDeskRecsPotMP, simulationResultPotMP)
              )
            })),
          props.simulationResultPotRCP(simulationResultPotMP => {
            props.crunchResultPotRCP(crunchResultPotMP => {
              props.labelsPotRCP(labelsPotMP =>
                <.div(^.cls := "user-desk-recs-chart",
                  labelsPotMP().renderReady(labels => DeskRecsChart.userSimulationWaitTimesChart(props.terminalName, props.queueName, props.airportConfig, labels, simulationResultPotMP, crunchResultPotMP)))
              )
            })
          })
        )))
  }

  def terminalQueueUserDeskRecsComponent() = {
    val airportConfigPotRCP = SPACircuit.connect(_.airportConfig)
    airportConfigPotRCP(airportConfigPotMP => {
      <.div(
        airportConfigPotMP().renderEmpty("Hello empty"),
        airportConfigPotMP().renderPending(_ => "Hello pending"),
        airportConfigPotMP().renderReady((airportConfig: AirportConfig) => {
          val queueUserDeskRecProps: Seq[QueueUserDeskRecsComponent.Props] = airportConfig.terminalNames.flatMap { terminalName =>
            airportConfig.queues.map { queueName =>
              val labelsPotRCP = SPACircuit.connect(_.workload.map(_.labels))
              val crunchResultPotRCP = SPACircuit.connect(_.queueCrunchResults.getOrElse(terminalName, Map()).getOrElse(queueName, Empty).flatMap(_._1))
              val userDeskRecsPotRCP = SPACircuit.connect(_.userDeskRec.getOrElse(terminalName, Map()).getOrElse(queueName, Empty))
              val flightsPotRCP = SPACircuit.connect(_.flights)
              val simulationResultPotRCP = SPACircuit.connect(_.simulationResult.getOrElse(terminalName, Map()).getOrElse(queueName, Empty))
              val userDeskRecsRowsPotRCP = makeUserDeskRecRowsPotRCP(terminalName, queueName)
              val airportInfoPotsRCP = SPACircuit.connect(_.airportInfos)
              QueueUserDeskRecsComponent.Props(
                terminalName,
                queueName,
                userDeskRecsRowsPotRCP,
                airportConfig,
                airportInfoPotsRCP,
                labelsPotRCP,
                crunchResultPotRCP,
                userDeskRecsPotRCP, flightsPotRCP, simulationResultPotRCP)
            }
          }
          <.div(
            ^.key := "UserDeskRecsWrapper",
            queueUserDeskRecProps.map(QueueUserDeskRecsComponent.component(_))
          )
        }))
    })
  }


  def makeUserDeskRecRowsPotRCP(terminalName: TerminalName, queueName: QueueName): ReactConnectProxy[Pot[List[UserDeskRecsRow]]] = {
    val userDeskRecRowsPotRCP = SPACircuit.connect(model => {
      val potRows: Pot[List[List[Any]]] = for {
        times <- model.workload.map(_.timeStamps(terminalName))
        qcr: (Pot[CrunchResult], Pot[UserDeskRecs]) <- model.queueCrunchResults.getOrElse(terminalName, Map()).getOrElse(queueName, Empty)
        qur: UserDeskRecs <- model.userDeskRec.getOrElse(terminalName, Map()).getOrElse(queueName, Empty)
        simres: SimulationResult <- model.simulationResult.getOrElse(terminalName, Map()).getOrElse(queueName, Ready(SimulationResult(qcr._1.get.recommendedDesks.map(rd => DeskRec(0, rd)), qcr._1.get.waitTimes)))
        potcr: Pot[CrunchResult] = qcr._1
        potdr: Pot[UserDeskRecs] = qcr._2
        cr: CrunchResult <- potcr
        dr: UserDeskRecs <- potdr
      } yield {
        val aDaysWorthOfTimes: Seq[Long] = DeskRecsChart.takeEvery15th(times).take(model.slotsInADay)
        val every15thRecDesk: Seq[Int] = DeskRecsChart.takeEvery15th(cr.recommendedDesks)
        val every15thCrunchWaitTime: Iterator[Int] = cr.waitTimes.grouped(model.minutesInASlot).map(_.max)
        val every15thSimWaitTime: Iterator[Int] = simres.waitTimes.grouped(model.minutesInASlot).map(_.max)
        val allRows = (aDaysWorthOfTimes :: every15thRecDesk :: qur.items :: every15thCrunchWaitTime :: every15thSimWaitTime :: Nil).transpose
        allRows
      }
      val is: Pot[List[UserDeskRecsRow]] = for (rows <- potRows) yield {
        rows.map(row => row match {
          case (time: Long) :: (crunchDeskRec: Int) :: (userDeskRec: DeskRecTimeslot) :: (waitTimeWithUserDeskRec: Int) :: (waitTimeWithCrunchDeskRec: Int) :: Nil =>
            UserDeskRecsRow(time, crunchDeskRec, userDeskRec, waitTimeWithUserDeskRec, waitTimeWithCrunchDeskRec)
          case default =>
            log.error(s"match error $default")
            throw new Exception(s"fail on $default")
        })
      }
      is
    })
    userDeskRecRowsPotRCP
  }

}

