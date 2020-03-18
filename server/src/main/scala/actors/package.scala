import drt.shared.CrunchApi.{MinutesContainer, MinutesLike}
import drt.shared.{SDateLike, Terminals}

import scala.concurrent.Future

package object actors {
  type MinutesLookup = (Terminals.Terminal, SDateLike) => Future[Option[MinutesContainer]]
  type MinutesUpdate = (Terminals.Terminal, SDateLike, MinutesContainer) => Future[Any]
}
