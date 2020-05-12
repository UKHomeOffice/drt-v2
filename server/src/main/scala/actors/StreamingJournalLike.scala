package actors

import akka.persistence.inmemory.query.scaladsl.InMemoryReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.{EventsByPersistenceIdQuery, ReadJournal}
import com.typesafe.config.Config
import play.api.Configuration

trait StreamingJournalLike {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery
  type ReadJournalType <: ReadJournalWithEvents
  val id: String
}

object StreamingJournal {
  def forConfig(config: Configuration): StreamingJournalLike =
    if (config.get[Boolean]("persistence.use-in-memory"))
      InMemoryStreamingJournal
    else
      DbStreamingJournal
}

object DbStreamingJournal extends StreamingJournalLike {
  override type ReadJournalType = JdbcReadJournal
  override val id: String = JdbcReadJournal.Identifier
}

object InMemoryStreamingJournal extends StreamingJournalLike {
  override type ReadJournalType = InMemoryReadJournal
  override val id: String = InMemoryReadJournal.Identifier
}
