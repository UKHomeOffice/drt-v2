package drt.client.modules

import japgolly.scalajs.react.extra.router._

/**
  * Copied from: https://github.com/japgolly/scalajs-react/issues/372#issuecomment-373516596
 */
object AdditionalDsl {
  import StaticDsl._

  private val queryCaptureRegex = "(:?(\\?.*)|())#?|$"

  /**
    * Captures the query portion of the URL to a param map.
    * Note that this is not a strict capture, URLs without a query string will still be accepted,
    * and the parameter map will simply by empty.
    */
  def queryToMap: RouteB[Map[String, String]] =
    new RouteB[Map[String, String]](
      queryCaptureRegex,
      1,
      capturedGroups => {
        val capturedQuery = capturedGroups(0).replaceFirst("\\?", "")
        val params = capturedQuery.split("&").filter(_.nonEmpty) map { param =>
          // Note that it is possible for there to be just a key and no value
          val key = param.takeWhile(_ != '=')
          val value = param.drop(key.length + 1)
          key -> value
        }
        Some(params.toMap)
      },
      paramsMap => {
        paramsMap.foldLeft("")((str, param) => {
          if (str.isEmpty) {
            s"?${param._1}=${param._2}"
          } else {
            str + s"&${param._1}=${param._2}"
          }
        })
      }
    )
}