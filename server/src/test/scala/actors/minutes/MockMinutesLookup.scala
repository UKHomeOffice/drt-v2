package actors.minutes

import actors.GetState
import actors.daily.{RequestAndTerminate, TerminalDayStaffActor}
import actors.minutes.MinutesActorLike.MinutesLookup
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.UtcDate
import services.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}

object MockMinutesLookup {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  def cmLookup(mockData: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = {
    val byDay: Map[UtcDate, Iterable[CrunchApi.MinuteLike[CrunchMinute, TQM]]] = mockData.minutes.groupBy {
      case m => SDate(m.minute).toUtcDate
    }
    (terminal: Terminal, date: SDateLike, maybePit: Option[MillisSinceEpoch]) => {
      Future{
        byDay.get(date.toUtcDate).map{
          mins =>MinutesContainer[CrunchMinute, TQM](mins)
        }
      }
    }
  }

  def smLookup(mockData: MinutesContainer[StaffMinute, TM]): MinutesLookup[StaffMinute, TM] = {
    val byDay: Map[UtcDate, Iterable[CrunchApi.MinuteLike[StaffMinute, TM]]] = mockData.minutes.groupBy {
      case m => SDate(m.minute).toUtcDate
    }
    (terminal: Terminal, date: SDateLike, maybePit: Option[MillisSinceEpoch]) => {
      Future{
        byDay.get(date.toUtcDate).map{
          mins =>MinutesContainer[StaffMinute, TM](mins)
        }
      }
    }
  }

}

