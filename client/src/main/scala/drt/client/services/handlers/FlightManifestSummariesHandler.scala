package drt.client.services.handlers

import diode._
import drt.client.actions.Actions._
import drt.client.services.DrtApi
import drt.shared.ArrivalKey
import drt.shared.api.FlightManifestSummary
import io.lemonlabs.uri.QueryString
import upickle.default._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class FlightManifestSummariesHandler[M](modelRW: ModelRW[M, Map[ArrivalKey, FlightManifestSummary]]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetManifestSummariesForDate(date) =>
      val request = Effect(DrtApi.get(s"manifest-summaries/$date/summary")
        .map { response =>
          val summaries = read[Set[FlightManifestSummary]](response.responseText)
          SetManifestSummaries(summaries)
        })
      effectOnly(request)

    case GetManifestSummaries(allKeys) =>
      val effects = allKeys.grouped(10).map { keys =>
        val str = write(keys)
        val keysEncoded = QueryString(Vector(("keys", Option(str)))).toString()
        Effect(DrtApi.get(s"manifest-summaries?$keysEncoded")
          .map { response =>
            val summaries = read[Set[FlightManifestSummary]](response.responseText)
            SetManifestSummaries(summaries)
          })
      }.toList

      effects.headOption match {
        case Some(head) => effectOnly(effects.drop(1).foldLeft(new EffectSeq(head, Seq(), queue))(_ >> _))
        case None => noChange
      }

    case SetManifestSummaries(manifestSummaries) =>
      println(s"Updating manifest summaries with ${manifestSummaries.size} summaries")
      val existing = value
      updated(existing ++ manifestSummaries.map(ms => ms.arrivalKey -> ms))
  }
}
