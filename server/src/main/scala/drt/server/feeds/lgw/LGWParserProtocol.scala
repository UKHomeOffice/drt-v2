package drt.server.feeds.lgw

import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol


trait LGWParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val gatwickAzureTokenFormat = jsonFormat4(GatwickAzureToken)
}
object LGWParserProtocol extends LGWParserProtocol
