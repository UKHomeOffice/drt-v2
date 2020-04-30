package actors.daily

import akka.persistence.query.scaladsl.{EventsByPersistenceIdQuery, ReadJournal}

object ReadJournalTypes {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery
}
