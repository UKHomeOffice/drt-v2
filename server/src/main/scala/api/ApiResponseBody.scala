package api

import play.api.libs.json.Json._
import play.api.libs.json.OWrites

case class ApiResponseBody(message: String)
object ApiResponseBody {
  implicit val w: OWrites[ApiResponseBody] = writes[ApiResponseBody]
}
