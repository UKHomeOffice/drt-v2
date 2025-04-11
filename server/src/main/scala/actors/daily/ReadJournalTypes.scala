package actors.daily

import org.apache.pekko.persistence.query.scaladsl.{EventsByPersistenceIdQuery, ReadJournal}

object ReadJournalTypes {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery
}
