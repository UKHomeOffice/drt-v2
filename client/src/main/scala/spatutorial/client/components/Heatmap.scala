package spatutorial.client.components

import diode.data.{Empty, Pot, Ready}
import diode.react._
import japgolly.scalajs.react.vdom.svg.{all => s}
import japgolly.scalajs.react.vdom.{all => html, prefix_<^}
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react._
import org.scalajs.dom.svg.{G, Text, RectElement, SVG}
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

  //  case class Props(
  //                    terminalName: TerminalName,
  //                    userDeskRecsRowPotRCP: ReactConnectProxy[Pot[List[UserDeskRecsRow]]],
  //                    airportConfig: AirportConfig,
  //                    airportInfoPotsRCP: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
  //                    labelsPotRCP: ReactConnectProxy[Pot[IndexedSeq[String]]],
  //                    crunchResultPotRCP: ReactConnectProxy[Map[QueueName, Pot[CrunchResult]]],
  //                    userDeskRecsPotRCP: ReactConnectProxy[Map[QueueName, Pot[UserDeskRecs]]],
  //                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
  //                    simulationResultPotRCP: ReactConnectProxy[Pot[SimulationResult]]
  //                  )
  case class Series(name: String, data: IndexedSeq[Double])

  case class Props(width: Int = 960, height: Int, numberOfBlocks: Int = 24,
                   series: Seq[Series],
                   scaleFunction: (Double) => Int)

  val colors = Vector("#D3F8E6", "#BEF4CC", "#A9F1AB", "#A8EE96", "#B2EA82", "#C3E76F", "#DCE45D",
    "#E0C54B", "#DD983A", "#DA6429", "#D72A18")

  def getSeries(crunchResult: CrunchResult, blockSize: Int = 60) = {
    val maxRecDesksPerPeriod = crunchResult.recommendedDesks.grouped(blockSize).map(_.max).toVector
    maxRecDesksPerPeriod
  }

  def bucketScale(maxValue: Double)(bucketValue: Double): Int = {
    //    log.info(s"bucketScale: ($bucketValue * ${bucketScaleDev(colors.length - 1, maxValue.toInt)}")
    (bucketValue * bucketScaleDev(colors.length - 1, maxValue.toInt)).toInt
  }

  case class RectProps(serie: Series, numberofblocks: Int, gridSize: Int, props: Props, sIndex: Int)

  def HeatmapRects = ReactComponentB[RectProps]("HeatmapRectsSeries")
    .render_P(props =>
      s.g(^.key := props.serie.name + "-" + props.sIndex, getRects(props.serie, props.numberofblocks, props.gridSize, props.props, props.sIndex))
    ).build

  def getRects(serie: Series, numberofblocks: Int, gridSize: Int, props: Props, sIndex: Int): vdom.ReactTagOf[G] = {
    log.info(s"Rendering rects for $serie")
    Try {
      val rects = (0 until numberofblocks).map(idx => {
        val deskRecsThisHour = serie.data(idx)
        //            log.info(s"${serie.name} deskRecsThisHour: ${deskRecsThisHour} hour: $idx")
        val colorBucket = props.scaleFunction(deskRecsThisHour) //Math.floor((deskRecsThisHour * bucketScale)).toInt
        val colors1 = colors(colorBucket)
        log.info(s"colorbucket  $deskRecsThisHour ${colorBucket} $colors1")

        val halfGrid: Int = gridSize / 2

        s.g(
          ^.key := s"rect-${serie.name}-$idx",
          s.rect(
            ^.key := s"${serie.name}-$idx",
            s.stroke := "black",
            s.strokeWidth := "1px",
            s.x := idx * gridSize,
            s.y := sIndex * gridSize,
            s.rx := 4,
            s.ry := 4,
            s.width := gridSize,
            s.height := gridSize,
            s.fill := colors1),
          s.text(deskRecsThisHour,
            s.x := idx * gridSize, s.y := sIndex * gridSize,
            s.transform := s"translate(${halfGrid}, ${halfGrid})"
          )
        )
      })
      val label: vdom.ReactTagOf[Text] = s.text(
        ^.key := s"${serie.name}",
        serie.name,
        s.x := 0,
        s.y := sIndex * gridSize,
        s.transform := s"translate(-100, ${gridSize / 1.5})") //, s.style := "text-anchor: middle")

      val allObj = s.g(label, rects)
      allObj
    } match {
      case Failure(e) =>
        log.error("failure in heatmap: " + e.toString)
        throw e
      case Success(success) =>
        success
    }
  }

  val heatmap = ReactComponentB[Props]("Heatmap")
    .renderP((_, props) => {
      try {
        log.info(s"!!!! rendering heatmap")
        val margin = 200
        val componentWidth = props.width + margin
        val mult = 1
        val numberofblocks: Int = props.numberOfBlocks * mult
        val size: Int = 60 / mult
        val gridSize = (componentWidth - margin) / numberofblocks

        val rectsAndLabels = props.series.zipWithIndex.map { case (serie, sIndex) => {
          log.info(s"Generating ${serie.name} as $sIndex")
          val queueName = serie.name
          val rects = HeatmapRects(RectProps(serie, numberofblocks, gridSize, props, sIndex))
          rects
        }
        }

        val hours: IndexedSeq[vdom.ReactTagOf[Text]] = (0 until 24).map(x =>
          s.text(^.key := s"bucket-$x", f"${x}%02d", s.x := x * gridSize, s.y := 0,
            s.transform := s"translate(${gridSize / 2}, -6)"))

        <.div(
          s.svg(
            ^.key := "heatmap",
            s.height := props.height,
            s.width := componentWidth, s.g(s.transform := "translate(200, 50)", rectsAndLabels.toList, hours.toList)))
      } catch {
        case e: Exception =>
          log.error("Issue in heatmap", e)
          throw e
      }
    }).build
}

