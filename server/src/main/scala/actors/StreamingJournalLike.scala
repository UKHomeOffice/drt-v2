package actors

import akka.persistence.inmemory.query.scaladsl.InMemoryReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.{EventsByPersistenceIdQuery, ReadJournal}

trait StreamingJournalLike {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery
  type ReadJournalType <: ReadJournalWithEvents
  val id: String
}

object ProdStreamingJournal extends StreamingJournalLike {
  override type ReadJournalType = JdbcReadJournal
  override val id: String = JdbcReadJournal.Identifier
}

object InMemoryStreamingJournal extends StreamingJournalLike {
  override type ReadJournalType = InMemoryReadJournal
  override val id: String = InMemoryReadJournal.Identifier
}
