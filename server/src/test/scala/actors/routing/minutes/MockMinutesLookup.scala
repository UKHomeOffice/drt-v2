package actors.routing.minutes

import actors.routing.minutes.MinutesActorLike.MinutesLookup
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.time.UtcDate
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}

object MockMinutesLookup {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  def cmLookup(mockData: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = {
    val byDay = mockData.minutes.groupBy(m => SDate(m.minute).toUtcDate)
    (terminalDate: (Terminal, UtcDate), _: Option[MillisSinceEpoch]) => {
      val (_, date) = terminalDate
      Future {
        byDay.get(date).map(MinutesContainer[CrunchMinute, TQM])
      }
    }
  }

  def smLookup(mockData: MinutesContainer[StaffMinute, TM]): MinutesLookup[StaffMinute, TM] = {
    val byDay = mockData.minutes.groupBy(m => SDate(m.minute).toUtcDate)
    (terminalDate: (Terminal, UtcDate), _: Option[MillisSinceEpoch]) => {
      val (_, date) = terminalDate
      Future {
        byDay.get(date).map(MinutesContainer[StaffMinute, TM])
      }
    }
  }

}

