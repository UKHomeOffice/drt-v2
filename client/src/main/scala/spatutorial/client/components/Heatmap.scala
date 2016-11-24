package spatutorial.client.components

import diode.data.{Empty, Pot, Ready}
import diode.react._
import japgolly.scalajs.react.vdom.svg.{all => s}
import japgolly.scalajs.react.vdom.{all => html}
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components.DeskRecsTable.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._

import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined
import scala.util.{Success, Try, Failure}

object Heatmap {
  def bucketScaleDev(n: Int, d: Int): Float = n.toFloat / d

  val heatmap = ReactComponentB[QueueUserDeskRecsComponent.Props]("Heatmap")
    .renderP((_, props) => {
      try {
        val margin = 200
        val componentWidth = 960 + margin
        val mult = 1
        val numberofblocks: Int = 24 * mult
        val size: Int = 60 / mult
        val gridSize = (componentWidth - margin) / numberofblocks

        val colors = Vector(
        "#D3F8E6",
        "#BEF4CC",

        "#A9F1AB",

        "#A8EE96",

        "#B2EA82",

        "#C3E76F",

        "#DCE45D",

        "#E0C54B",

        "#DD983A",

        "#DA6429",

        "#D72A18")

//        val colors = Vector("#ffffd9", "#edf8b1", "#c7e9b4", "#7fcdbb", "#41b6c4", "#1d91c0", "#225ea8", "#253494", "#081d58")
        ////      val rects = props.crunchResultPotRCP(crmp =>
        ////        crmp().get.recommendedDesks().zipWithIndex().map {
        ////          case (desk, idx: Int) =>
        <.div(props.crunchResultPotRCP(crunchResultMp =>
          <.div(
            crunchResultMp().renderEmpty("Awaiting crunch"),
            crunchResultMp().renderReady(crunchResult => {
              Try {
                val maxRecDesksPerHour = crunchResult.recommendedDesks.grouped(size).map(_.max).toVector
                log.info(s"maxRecDesksPerHours eh? ${maxRecDesksPerHour.length}")
                val maxRecDesksToday: Float = maxRecDesksPerHour.max
                val double: Double = (colors.length - 1) .toDouble
                def bucketScale = bucketScaleDev(colors.length-1, maxRecDesksToday.toInt)
                log.info(s"maxRecDesksToday ${double / maxRecDesksToday} ${maxRecDesksToday}, $bucketScale")
                val rects = (0 until numberofblocks).map(idx => {
                  val deskRecsThisHour = maxRecDesksPerHour(idx)
                  val colorBucket = Math.floor((deskRecsThisHour * bucketScale)).toInt
                  log.info(s"$deskRecsThisHour * $bucketScale = colorBucket ${colorBucket}")
                  s.rect(
                    s.stroke := "black",
                    s.strokeWidth := "1px",
                    s.x := idx * gridSize,
                    s.y := 0,
                    s.width := gridSize,
                    s.height := gridSize,
                    s.fill := colors(colorBucket))
                })
                val label = s.text(props.queueName, s.x := 0, s.y := 0, s.transform := s"translate(-100, ${gridSize / 1.5})")//, s.style := "text-anchor: middle")
                val hours = (0 until 24).map(x => s.text(f"${x}%02d", s.x := x * gridSize, s.y := 0,
                    s.transform := s"translate(${gridSize / 2}, -6)"))

                s.svg(
                  s.height := gridSize + 40,
                  s.width := componentWidth, s.g(s.transform := "translate(200, 50)", rects, label, hours))
              } match {
                case Failure(e) => log.error("failure in heatmap: " + e.toString)
                  <.div(s"Something went wrong ${e.toString}")
                case Success(success) =>
                  success
              }
            }))))
      } catch {
        case e: Exception =>
          log.error("Issue in heatmap", e)
          throw e
      }
    }
    ).build
}
