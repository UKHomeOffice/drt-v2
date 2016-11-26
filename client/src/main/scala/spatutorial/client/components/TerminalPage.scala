package spatutorial.client.components

import diode.data.{Pot, Ready}
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import spatutorial.client.SPAMain.{Loc, TerminalLoc}
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.{RootModel, SPACircuit}
import spatutorial.client.logger._

object TerminalPage {

  case class Props(routeData: TerminalLoc, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      <.div(
        heatmapOfDeskRecs(),
        heatmapOfDeskRecsVsActualDesks(),
        heatmapOfWaittimes(),
        <.div(
          ^.className := "terminal-desk-recs-container",
          TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(props.routeData.id)
        )
      )
    }
  }

  def heatmapOfWaittimes() = {
    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(waitTimes(_))
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      val p: List[Series] = serMP()
      p match {
        case Nil =>
          <.h4("No waittimes in simulation yet")
        case waitTimes =>
          val maxRatioAcrossAllSeries = p.map(_.data.max).max
          log.info(s"Got max waittime of ${maxRatioAcrossAllSeries}")
          <.div(
            <.h4("heatmap of wait times"),
            Heatmap.heatmap(Heatmap.Props(series = p, height = 200,
              scaleFunction = Heatmap.bucketScale(maxRatioAcrossAllSeries)))
          )
      }
    })
  }

  def heatmapOfDeskRecs() = {

    val seriiRCP: ReactConnectProxy[List[Series]] = SPACircuit.connect(_.queueCrunchResults.flatMap {
      case (terminalName, queueCrunchResult) =>
        queueCrunchResult.collect {
          case (queueName, Ready((Ready(crunchResult), _))) =>
            val series = Heatmap.seriesFromCrunchResult(crunchResult)
            Series(terminalName + "/" + queueName, series.map(_.toDouble))
        }
    }.toList)
    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      <.div(
        <.h4("heatmap of desk recommendations"),
        Heatmap.heatmap(Heatmap.Props(series = serMP(), height = 200, scaleFunction = Heatmap.bucketScale(20)))
      )
    })
  }


  def heatmapOfDeskRecsVsActualDesks() = {
    val seriiRCP = SPACircuit.connect {
      deskRecsVsActualDesks(_)
    }

    seriiRCP((serMP: ModelProxy[List[Series]]) => {
      val p: List[Series] = serMP()
      val maxRatioAcrossAllSeries = p.map(_.data.max).max
      <.div(
        <.h4("heatmap of ratio of desk rec to actual desks"),
        Heatmap.heatmap(Heatmap.Props(series = p, height = 200,
          scaleFunction = Heatmap.bucketScale(maxRatioAcrossAllSeries))))
    })
  }

  def waitTimes(rootModel: RootModel): List[Series] = {
    //      Series("T1/eeadesk", Vector(2)) :: Nil
    val terminalName = "T1"
    log.info(s"simulation results ${rootModel.simulationResult}")
    val terminalQueues = rootModel.simulationResult.getOrElse(terminalName, Map())
    val result: Iterable[Series] = for {
      queueName <- terminalQueues.keys
      simResult <- rootModel.simulationResult(terminalName)(queueName)
      waitTimes = simResult.waitTimes
    } yield {
      Series(terminalName + "/" + queueName,
        waitTimes.grouped(60).map(_.max.toDouble).toVector)
    }
    result.toList
  }

  def deskRecsVsActualDesks(rootModel: RootModel): List[Series] = {
    //      Series("T1/eeadesk", Vector(2)) :: Nil
    val terminalName = "T1"
    val result: Iterable[Series] = for {
      queueName <- rootModel.queueCrunchResults(terminalName).keys
      queueCrunch <- rootModel.queueCrunchResults(terminalName)(queueName)
      userDesks <- rootModel.userDeskRec(terminalName)(queueName)
      recDesksPair <- queueCrunch._1
      recDesks = recDesksPair.recommendedDesks
      userDesksVals = userDesks.items.map(_.deskRec)
    } yield {
      log.info(s"UserDeskRecs Length: ${userDesks.items.length}")
      val recDesks15MinBlocks = recDesks.grouped(15).map(_.max).toList
      val zippedRecAndUserBy15Mins = recDesks15MinBlocks.zip(userDesksVals)
      //      val zippedRecAndUser = maxRecDesks.zip(minUserDesks).map(x => x._1.toDouble / x._2)
      val ratioRecToUserPerHour = zippedRecAndUserBy15Mins.grouped(4).map(
        x => x.map(y => y._1.toDouble / y._2).max)
      Series(terminalName + "/" + queueName, ratioRecToUserPerHour.toVector)
    }
    result.toList
  }

  //  def deskRecsVsActualDesks(model: RootModel) = {
  //    val serii: Pot[Seq[(TerminalName, QueueName, IndexedSeq[(Int, DeskRecTimeslot)])]] = (for {
  //      terminalName: TerminalName <- model.queueCrunchResults.keys
  //      queueName: QueueName <- model.queueCrunchResults(terminalName).keys
  //      terminalCrunchResults: Option[Map[QueueName, Pot[(Pot[CrunchResult], Pot[UserDeskRecs])]]] = model.queueCrunchResults.get(terminalName)
  //      crunchAndDeskRecs: (Pot[CrunchResult], Pot[UserDeskRecs]) <- terminalCrunchResults.get(queueName)
  //      potCrunchResult: Pot[CrunchResult] = crunchAndDeskRecs._1
  //      queueCrunchResult: CrunchResult <- potCrunchResult
  //      optQueueUserDeskRecs = model.userDeskRec.get(terminalName)
  //      terminalUserDeskRecs: QueueUserDeskRecs <- optQueueUserDeskRecs
  //      queueUserDeskPot: Pot[UserDeskRecs] <- terminalUserDeskRecs.get(queueName)
  //      userDesks: UserDeskRecs <- queueUserDeskPot.toOption
  //    } yield {
  //      val zip: IndexedSeq[(Int, DeskRecTimeslot)] = queueCrunchResult.recommendedDesks.zip(userDesks.items)
  //      (terminalName, queueName, zip)
  //    }).flatten
  //    serii.map {
  //      case (terminalName, queueName, crunchDeskAndUserDesk) =>
  //        val series: Vector[Double] = Heatmap.seriesFromCrunchAndUserDesk(crunchDeskAndUserDesk)
  //        Series(terminalName + "/" + queueName, series)
  //        serii
  //    }
  //  }

  def apply(terminal: TerminalLoc, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminal, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
