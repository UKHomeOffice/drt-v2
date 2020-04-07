import drt.shared.CrunchApi.MinutesContainer
import drt.shared.{SDateLike, Terminals}

import scala.concurrent.Future

package object actors {
  type MinutesLookup[A, B] = (Terminals.Terminal, SDateLike) => Future[Option[MinutesContainer[A, B]]]
  type MinutesUpdate[A, B] = (Terminals.Terminal, SDateLike, MinutesContainer[A, B]) => Future[MinutesContainer[A, B]]
}
