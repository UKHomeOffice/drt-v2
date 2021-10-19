package actors

import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import play.api.Configuration

trait StreamingJournalLike {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery with CurrentEventsByPersistenceIdQuery
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
  override type ReadJournalType = PersistenceTestKitReadJournal
  override val id: String = PersistenceTestKitReadJournal.Identifier
}
