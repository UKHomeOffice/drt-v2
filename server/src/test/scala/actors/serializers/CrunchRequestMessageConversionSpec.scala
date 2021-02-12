package actors.serializers

import actors.serializers.CrunchRequestMessageConversion.{crunchRequestToMessage, crunchRequestsFromMessages, maybeCrunchRequestFromMessage, removeCrunchRequestMessage}
import drt.shared.dates.LocalDate
import org.specs2.mutable.Specification
import server.protobuf.messages.CrunchState.RemoveCrunchRequestMessage
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest

class CrunchRequestMessageConversionSpec extends Specification {
  "When converting to a message and then back again the original data should remain unchanged" >> {
    "Given a CrunchRequest" >> {
      val crunchRequest = CrunchRequest(LocalDate(2021, 5, 1), 120, 1440)
      val message = crunchRequestToMessage(crunchRequest)
      val restored = maybeCrunchRequestFromMessage(message)

      restored === Option(crunchRequest)
    }

    "Given multiple CrunchRequests" >> {
      val crunchRequests = Iterable(
        CrunchRequest(LocalDate(2021, 5, 1), 120, 1440),
        CrunchRequest(LocalDate(2021, 5, 2), 120, 1440)
      )

      val messages = crunchRequests.map(crunchRequestToMessage)
      val restored = crunchRequestsFromMessages(messages)

      restored === crunchRequests
    }
  }

  "Given a CrunchRequest for 2021-05-01" >> {
    "When I ask for a removal message" >> {
      "Then I should get a removal message for the same date" >> {
        val crunchRequest = CrunchRequest(LocalDate(2021, 5, 1), 120, 1440)
        val removalMessageDate = removeCrunchRequestMessage(crunchRequest) match {
          case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day)) =>
            LocalDate(year, month, day)
        }

        removalMessageDate === crunchRequest.localDate
      }
    }
  }
}
