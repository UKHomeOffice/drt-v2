package controllers

import com.google.inject.Inject
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import play.filters.csp.{CSPReportActionBuilder, ScalaCSPReport}

class CSPReportController @Inject() (cc: ControllerComponents, cspReportAction: CSPReportActionBuilder)
  extends AbstractController(cc) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  val report: Action[ScalaCSPReport] = cspReportAction { request =>
    val report = request.body
    val logText= s"{CSP violation: violated-directive = ${report.violatedDirective}, " +
      s"blocked = ${report.blockedUri}, " +
      s"policy = ${report.originalPolicy}}"
    logger.warn(logText)
    Ok("{}").as(JSON)
  }
}