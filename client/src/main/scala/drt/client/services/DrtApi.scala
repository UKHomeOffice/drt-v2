package drt.client.services

import drt.client.SPAMain
import org.scalajs.dom
import org.scalajs.dom.{XMLHttpRequest, document, html}

import scala.concurrent.Future

object DrtApi {
  def get(resource: String): Future[XMLHttpRequest] = dom.ext.Ajax.get(url = SPAMain.absoluteUrl(resource))

  def delete(resource: String): Future[XMLHttpRequest] = dom.ext.Ajax.delete(url = SPAMain.absoluteUrl(resource),
    headers = Map("Csrf-Token" -> document.getElementById("csrfToken").asInstanceOf[html.Input].value)
  )

  def post(resource: String, json: String): Future[XMLHttpRequest] = dom.ext.Ajax.post(SPAMain.absoluteUrl(resource), json,
    headers = Map("Csrf-Token" -> document.getElementById("csrfToken").asInstanceOf[html.Input].value)
  )
}
