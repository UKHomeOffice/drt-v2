package drt.client.components

import diode.data.{Pending, Pot, Ready}
import diode.react._
import drt.client.components.FlightTableRow.Props
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.TagOf
import org.scalajs.dom.svg.{G, Text}
import drt.client.components.Heatmap.Series
import drt.client.logger._
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel.QueueCrunchResults
import drt.client.services._
import drt.shared.FlightsApi._
import drt.shared._
import japgolly.scalajs.react.extra.Reusability

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
              val maxAcrossAllSeries = emptySafeMax(heatMapSeries.map(x => emptySafeMax(x.data)))
              log.info(s"Got max workload of ${maxAcrossAllSeries}")
              <.div(
                Heatmap.heatmap(Heatmap.Props(series = heatMapSeries.sortBy(_.name), scaleFunction = Heatmap.bucketScale(maxAcrossAllSeries)))
              )
            case None =>
              <.div(s"No workloads for ${terminalName}")
          }
        }))
    })
  }

  def heatmapOfPaxloads(terminalName: TerminalName) = {
    val workloadsRCP = SPACircuit.connect(_.workloadPot)
    workloadsRCP((workloadsMP: ModelProxy[Pot[Workloads]]) => {
      <.div(
        workloadsMP().renderReady(wl => {
          wl.workloads.get(terminalName) match {
            case Some(terminalWorkload) =>
              val heatMapSeries = paxloads(terminalWorkload, terminalName)
              val maxAcrossAllSeries = heatMapSeries.map(x => emptySafeMax(x.data)).max
              log.info(s"Got max paxLoad of ${maxAcrossAllSeries}")
              log.info(s"heatmap terminalWorkloas ${terminalWorkload.keys}")
              <.div(
                Heatmap.heatmap(Heatmap.Props(series = heatMapSeries.sortBy(_.name), scaleFunction = Heatmap.bucketScale(maxAcrossAllSeries)))
              )
            case None =>
              <.div(s"No paxloads for ${terminalName}")
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
          val maxAcrossAllSeries = emptySafeMax(queueSeries.map(x => emptySafeMax(x.data)))
          log.info(s"Got max waittime of ${maxAcrossAllSeries}")
          <.div(
            Heatmap.heatmap(Heatmap.Props(series = queueSeries.sortBy(_.name), scaleFunction = Heatmap.bucketScale(maxAcrossAllSeries)))
          )
        }))
    })

  }

  def heatmapOfCrunchDeskRecs(terminalName: TerminalName) = {
    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(_.queueCrunchResults.getOrElse(terminalName, Map()).collect {
      case (queueName, Ready((Ready(crunchResult)))) =>
        val series = Heatmap.seriesFromCrunchResult(crunchResult)
        Series(terminalName + "/" + queueName, series.map(_.toDouble))
    }.toList.sortBy(_.name))
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      <.div(
        Heatmap.heatmap(Heatmap.Props(series = serMP().sortBy(_.name), scaleFunction = Heatmap.bucketScale(20)))
      )
    })
  }

  def heatmapOfStaffDeploymentDeskRecs(terminalName: TerminalName) = {
    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(_.staffDeploymentsByTerminalAndQueue.getOrElse(terminalName, Map()).collect {
      case (queueName, Ready(queueStaffDeployments)) =>
        val queustaffDeploymentItems = queueStaffDeployments.items
        //queueStaffDeployments are in 15 minute chunks
        val series = queustaffDeploymentItems.map(_.deskRec).grouped(4).map(_.max)
        Series(terminalName + "/" + queueName, series.map(_.toDouble).toIndexedSeq)
    }.toList.sortBy(x => x.name))
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      <.div(
        Heatmap.heatmap(Heatmap.Props(series = serMP().sortBy(_.name), scaleFunction = Heatmap.bucketScale(20)))
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
                  Heatmap.heatmap(Heatmap.Props(series = series.sortBy(_.name), scaleFunction = Heatmap.bucketScale(maxRatioAcrossAllSeries), valueDisplayFormatter = v => f"${v}%.1f")))
              }))
          })
        )
      )
    })
  }

  def chartDataFromWorkloads(workloads: Map[String, (Seq[WL], Seq[Pax])], minutesPerGroup: Int = 15,
                             paxloadWorkloadSelector: ((Seq[WL], Seq[Pax])) => Map[Long, Double]): Map[String, List[Double]] = {
    val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
    val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)
    //    val queueWorkloadsByMinute = WorkloadsHelpers.workloadPeriodByQueue(workloads, minutesRangeInMillis)
    val queueWorkloadsByMinute = WorkloadsHelpers.loadPeriodByQueue(workloads, minutesRangeInMillis, paxloadWorkloadSelector)
    val by15Minutes = queueWorkloadsByMinute.mapValues(
      (v) => v.grouped(minutesPerGroup).map(_.sum).toList
    )
    by15Minutes
  }

  def workloads(terminalWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])], terminalName: String): List[Series] = {
    log.info(s"!!!!looking up $terminalName in wls")
    val queueWorkloads: Predef.Map[String, List[Double]] = chartDataFromWorkloads(terminalWorkloads, 60, WorkloadsHelpers.workloadByMillis)
    val result: Iterable[Series] = for {
      (queue, work) <- queueWorkloads
    } yield {
      Series(terminalName + "/" + queue, work.toVector)
    }
    result.toList
  }

  def paxloads(terminalWorkloads: Map[QueueName, (Seq[WL], Seq[Pax])], terminalName: String): List[Series] = {
    log.info(s"!!!!looking up $terminalName in wls")
    val queueWorkloads: Predef.Map[String, List[Double]] = chartDataFromWorkloads(terminalWorkloads, 60, WorkloadsHelpers.paxloadByMillis)
    val result: Iterable[Series] = for {
      (queue, work) <- queueWorkloads
    } yield {
      Series(terminalName + "/" + queue, work.toVector)
    }
    result.toList
  }

  def waitTimes(simulationResult: Map[QueueName, Pot[SimulationResult]], terminalName: String): Pot[List[Series]] = {
    val result: Iterable[Series] = for {
      (queueName, simResultPot) <- simulationResult
      simResult <- simResultPot
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
      queueName: QueueName <- queueCrunchResults.keys
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

  import japgolly.scalajs.react.vdom.svg_<^.{< => s}
  import japgolly.scalajs.react.vdom.svg_<^.{^ => sv}

  def bucketScaleDev(n: Int, d: Int): Float = n.toFloat / d

  case class Series(name: String, data: IndexedSeq[Double])

  case class Props(width: Int = 960, numberOfBlocks: Int = 24, series: List[Series], scaleFunction: (Double) => Int, shouldShowRectValue: Boolean = true, valueDisplayFormatter: (Double) => String = _.toInt.toString) {
    def height = 50 * (series.length + 1)
  }

  implicit val doubleReuse = Reusability.double(0.01)
  implicit val doubleSeqReuse = Reusability.indexedSeq[IndexedSeq, Double]
  implicit val seriesReuse = Reusability.caseClass[Series]
  implicit val propsReuse = Reusability.caseClassExceptDebug[Props]('valueDisplayFormatter, 'scaleFunction)
  implicit val rectPropsReuse = Reusability.caseClass[RectProps]

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

  def HeatmapRects = ScalaComponent.builder[RectProps]("HeatmapRectsSeries")
    .render_P(props =>
      s.g(^.key := props.serie.name + "-" + props.sIndex,
        getRects(props.serie, props.numberofblocks, props.gridSize, props.props, props.sIndex))
    )
    .configure(Reusability.shouldComponentUpdate)
    .build

  def getRects(serie: Series, numberofblocks: Int, gridSize: Int, props: Props, sIndex: Int): TagOf[G] = {
    Try {
      val rects = serie.data.zipWithIndex.map {
        case (periodValue, idx) => {
          val colorBucket = props.scaleFunction(periodValue)
          val clippedUpperColorBucket = Math.min(colors.length - 1, colorBucket)
          val clippedColorBucket = Math.max(0, clippedUpperColorBucket)
          val colors1 = colors(clippedColorBucket)
          val halfGrid: Int = gridSize / 2
          s.g(
            ^.key := s"rect-${serie.name}-$idx",
            s.rect(
              ^.key := s"${serie.name}-$idx",
              sv.stroke := "black",
              sv.strokeWidth := "1px",
              sv.x := idx * gridSize,
              sv.y := sIndex * gridSize,
              sv.rx := 4,
              sv.ry := 4,
              sv.width := gridSize,
              sv.height := gridSize,
              sv.fill := colors1),
            if (props.shouldShowRectValue) {
              val verticalAlignmentOffset = 5
              s.text(props.valueDisplayFormatter(periodValue),
                sv.x := idx * gridSize, sv.y := sIndex * gridSize + verticalAlignmentOffset,
                sv.transform := s"translate(${halfGrid}, ${halfGrid})", sv.textAnchor := "middle")
            }
            else null
          )
        }
      }
      val label = s.text(
        ^.key := s"${serie.name}",
        serie.name,
        sv.x := 0,
        sv.y := sIndex * gridSize,
        sv.transform := s"translate(-100, ${gridSize / 1.5})", sv.textAnchor := "middle")


      val allObj = s.g(label, rects.toTagMod)
      allObj
    } match {
      case Failure(e) =>
        log.error("failure in heatmap: " + e.toString, e.asInstanceOf[Exception])
        throw e
      case Success(success) =>
        success
    }
  }

  val heatmap = ScalaComponent.builder[Props]("Heatmap")
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

        val hours = (0 until 24).map(x =>
          s.text(^.key := s"bucket-$x", f"${x.toInt}%02d", sv.x := x * gridSize, sv.y := 0,
            sv.transform := s"translate(${gridSize / 2}, -10)", sv.textAnchor := "middle"))

        <.div(
          s.svg(
            ^.key := "heatmap",
            sv.height := props.height,
            sv.width := componentWidth, s.g(sv.transform := "translate(180, 50)", rectsAndLabels.toTagMod, hours.toTagMod)))
      } catch {
        case e: Exception =>
          log.error("Issue in heatmap", e)
          throw e
      }
    })
    .configure(Reusability.shouldComponentUpdate)
    .build
}

