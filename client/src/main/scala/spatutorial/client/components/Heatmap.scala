package drt.client.components

import diode.data.{Pending, Pot, Ready}
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.vdom.svg.{all => s}
import org.scalajs.dom.svg.{G, Text}
import drt.client.components.Heatmap.Series
import drt.client.logger._
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.RootModel.QueueCrunchResults
import drt.client.services._
import drt.shared.FlightsApi._
import drt.shared.Queues.QueueType
import drt.shared._

import scala.collection.immutable.{IndexedSeq, Map, NumericRange, Seq}
import scala.util.{Failure, Success, Try}


object TerminalHeatmaps {
  def heatmapOfWorkloads(terminalName: TerminalName) = {
    val workloadsRCP = SPACircuit.connect(_.workloadPot)
    workloadsRCP((workloadsMP: ModelProxy[Pot[Workloads]]) => {
      <.div(
        workloadsMP().renderReady(wl => {
          wl.workloads.get(terminalName) match {
            case Some(terminalWorkload) =>
              val heatMapSeries = workloads(terminalWorkload, terminalName)
              val maxAcrossAllSeries = heatMapSeries.map(x => emptySafeMax(x.data)).max
              log.info(s"Got max workload of ${maxAcrossAllSeries}")
              <.div(
                Heatmap.heatmap(Heatmap.Props(series = heatMapSeries, scaleFunction = Heatmap.bucketScale(maxAcrossAllSeries)))
              )
            case None =>
              <.div(s"No workloads for ${terminalName}")
          }
        }))
    })
  }

  def emptySafeMax(data: Seq[Double]): Double = {
    data match {
      case Nil =>
        0d
      case d =>
        d.max
    }
  }

  def heatmapOfWaittimes(terminalName: TerminalName) = {
    val simulationResultRCP = SPACircuit.connect(_.simulationResult)
    simulationResultRCP(simulationResultMP => {

      log.info(s"simulation result keys ${simulationResultMP().keys}")
      val seriesPot: Pot[List[Series]] = waitTimes(simulationResultMP().getOrElse(terminalName, Map()), terminalName)
      <.div(
        seriesPot.renderReady(queueSeries => {
          val maxAcrossAllSeries = emptySafeMax(queueSeries.map(x => x.data.max))
          log.info(s"Got max waittime of ${maxAcrossAllSeries}")
          <.div(
            Heatmap.heatmap(Heatmap.Props(series = queueSeries, scaleFunction = Heatmap.bucketScale(maxAcrossAllSeries)))
          )
        }))
    })

  }

  def heatmapOfDeskRecs(terminalName: TerminalName) = {
    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(_.queueCrunchResults.getOrElse(terminalName, Map()).collect {
      case (queueName, Ready((Ready(crunchResult)))) =>
        val series = Heatmap.seriesFromCrunchResult(crunchResult)
        Series(terminalName + "/" + queueName, series.map(_.toDouble))
    }.toList)
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      <.div(
        Heatmap.heatmap(Heatmap.Props(series = serMP(), scaleFunction = Heatmap.bucketScale(20)))
      )
    })
  }

  def heatmapOfDeskRecsVsActualDesks(terminalName: TerminalName) = {
    val modelBitsRCP = SPACircuit.connect(rootModel => (
      rootModel.queueCrunchResults,
      rootModel.staffDeploymentsByTerminalAndQueue
    ))
    val shiftsRawRCP = SPACircuit.connect(rootModel => (rootModel.shiftsRaw))

    shiftsRawRCP((shiftsRawMP: ModelProxy[Pot[String]]) => {
      <.div(
        shiftsRawMP().renderReady(shiftsRaw =>
          modelBitsRCP(modelBitsMP => {
            val queueCrunchResults = modelBitsMP()._1.getOrElse(terminalName, Map())
            val userDeskRecs = modelBitsMP()._2.getOrElse(terminalName, Map())
            val seriesPot = deskRecsVsActualDesks(queueCrunchResults, userDeskRecs, terminalName)
            <.div(
              seriesPot.renderReady(series => {
                val maxRatioAcrossAllSeries = emptySafeMax(series.map(_.data.max)) + 1
                <.div(
                  Heatmap.heatmap(Heatmap.Props(series = series, scaleFunction = Heatmap.bucketScale(maxRatioAcrossAllSeries), valueDisplayFormatter = v => f"${v}%.1f")))
              }))
          })
        )
      )
    })
  }

  def chartDataFromWorkloads(workloads: Map[QueueType, QueuePaxAndWorkLoads], minutesPerGroup: Int = 15): Map[QueueType, List[Double]] = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    val queueWorkloadsByMinute = WorkloadsHelpers.workloadPeriodByQueue(workloads, minutesRangeInMillis)
    val by15Minutes = queueWorkloadsByMinute.mapValues(
      (v) => v.grouped(minutesPerGroup).map(_.sum).toList
    )
    by15Minutes
  }

  def workloads(terminalWorkloads: Map[QueueType, (Seq[WL], Seq[Pax])], terminalName: String): List[Series] = {
    log.info(s"!!!!looking up $terminalName in wls")
    val queueWorkloads: Map[QueueType, List[Double]] = chartDataFromWorkloads(terminalWorkloads, 60)
    val result: Iterable[Series] = for {
      (queue, work) <- queueWorkloads
    } yield {
      Series(terminalName + "/" + queue, work.toVector)
    }
    result.toList
  }

  def waitTimes(simulationResult: Map[QueueType, Pot[SimulationResult]], terminalName: String): Pot[List[Series]] = {
    val result: Iterable[Series] = for {
      queueName <- simulationResult.keys
      simResult <- simulationResult(queueName)
      waitTimes = simResult.waitTimes
    } yield {
      Series(terminalName + "/" + queueName,
        waitTimes.grouped(60).map(_.max.toDouble).toVector)
    }
    result.toList match {
      case Nil => Pending()
      case _ => Ready(result.toList)
    }
  }

  def deskRecsVsActualDesks(queueCrunchResults: QueueCrunchResults, userDeskRecs: QueueStaffDeployments, terminalName: TerminalName): Pot[List[Series]] = {
    log.info(s"deskRecsVsActualDesks")
    val result: Iterable[Series] = for {
      queueName: QueueType <- queueCrunchResults.keys
      queueCrunchPot: Pot[Pot[CrunchResult]] <- queueCrunchResults.get(queueName)
      queueCrunch: Pot[CrunchResult] <- queueCrunchPot.toOption
      userDesksPot: Pot[DeskRecTimeSlots] <- userDeskRecs.get(queueName)
      userDesks: DeskRecTimeSlots <- userDesksPot.toOption
      crunchDeskRecsPair: CrunchResult <- queueCrunch.toOption
      crunchDeskRecs: IndexedSeq[Int] = crunchDeskRecsPair.recommendedDesks
      userDesksVals: Seq[Int] = userDesks.items.map(_.deskRec)
    } yield {
      val crunchDeskRecs15MinBlocks = crunchDeskRecs.grouped(15).map(_.max).toList
      val zippedCrunchAndUserRecsBy15Mins = crunchDeskRecs15MinBlocks.zip(userDesksVals)
      val ratioCrunchToUserRecsPerHour = zippedCrunchAndUserRecsBy15Mins.grouped(4).map(
        (x: List[(Int, Int)]) => x.map(y => {
          y._1.toDouble / y._2
        }).sum / 4
      )
      log.info(s"-----deskRecsVsActualDesks: ${queueName}")
      Series(terminalName + "/" + queueName, ratioCrunchToUserRecsPerHour.toVector)
    }
    log.info(s"gotDeskRecsvsAct, ${result.toList.length}")
    result.toList match {
      case Nil => Pending()
      case _ => Ready(result.toList)
    }
  }
}

object Heatmap {

  def bucketScaleDev(n: Int, d: Int): Float = n.toFloat / d

  case class Series(name: String, data: IndexedSeq[Double])

  case class Props(width: Int = 960, numberOfBlocks: Int = 24, series: Seq[Series], scaleFunction: (Double) => Int, shouldShowRectValue: Boolean = true, valueDisplayFormatter: (Double) => String = _.toInt.toString) {
    def height = 50 * (series.length + 1)
  }

  val colors = Vector("#D3F8E6", "#BEF4CC", "#A9F1AB", "#A8EE96", "#B2EA82", "#C3E76F", "#DCE45D",
    "#E0C54B", "#DD983A", "#DA6429", "#D72A18")

  def seriesFromCrunchAndUserDesk(crunchDeskAndUserDesk: IndexedSeq[(Int, DeskRecTimeslot)], blockSize: Int = 60): Vector[Double] = {
    val maxRecVsMinUser = crunchDeskAndUserDesk.grouped(blockSize).map(x => (x.map(_._1).max, x.map(_._2.deskRec).min))
    val ratios = maxRecVsMinUser.map(x => x._1.toDouble / x._2.toDouble).toVector
    ratios
  }

  def seriesFromCrunchResult(crunchResult: CrunchResult, blockSize: Int = 60) = {
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
      val rects = serie.data.zipWithIndex.map {
        case (periodValue, idx) => {
          val colorBucket = props.scaleFunction(periodValue)
          val clippedColorBucket = Math.min(colors.length - 1, colorBucket)
          val colors1 = colors(clippedColorBucket)
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
            if (props.shouldShowRectValue) {
              val verticalAlignmentOffset = 5
              s.text(props.valueDisplayFormatter(periodValue),
                s.x := idx * gridSize, s.y := sIndex * gridSize + verticalAlignmentOffset,
                s.transform := s"translate(${halfGrid}, ${halfGrid})", s.textAnchor := "middle")
            }
            else null
          )
        }
      }
      val label: vdom.ReactTagOf[Text] = s.text(
        ^.key := s"${serie.name}",
        serie.name,
        s.x := 0,
        s.y := sIndex * gridSize,
        s.transform := s"translate(-100, ${gridSize / 1.5})", s.textAnchor := "middle")

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

        val rectsAndLabels = props.series.zipWithIndex.map { case (serie, sIndex) =>
          HeatmapRects(RectProps(serie, numberofblocks, gridSize, props, sIndex))
        }

        val hours: IndexedSeq[vdom.ReactTagOf[Text]] = (0 until 24).map(x =>
          s.text(^.key := s"bucket-$x", f"${x.toInt}%02d", s.x := x * gridSize, s.y := 0,
            s.transform := s"translate(${gridSize / 2}, -10)", s.textAnchor := "middle"))

        <.div(
          s.svg(
            ^.key := "heatmap",
            s.height := props.height,
            s.width := componentWidth, s.g(s.transform := "translate(180, 50)", rectsAndLabels.toList, hours.toList)))
      } catch {
        case e: Exception =>
          log.error("Issue in heatmap", e)
          throw e
      }
    }).build
}

