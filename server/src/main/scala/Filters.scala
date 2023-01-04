import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}

//@Singleton
//class Filters @Inject()(env: Environment, noCache: NoCacheFilter,context: Context)
//    extends BuiltInComponentsFromContext(context)
//    with HttpFiltersComponents
//    with CSPComponents {
//  //  override val filters: Seq[EssentialFilter] = Seq(noCache, SecurityHeaders.filter)
//  override def httpFilters: Seq[EssentialFilter] = super.httpFilters :+ cspFilter
//
//  lazy val router = Router.empty
//
//}

//object SecurityHeaders {
//  val default = "default-src 'self'"
//  val javaScript = "script-src 'self' 'nonce-Njg1Mjk1ZGYtNmUzOS00MTNiLWJmM2ItM2Q5NGFiYWYwNDVj' https://*.googletagmanager.com www.googletagmanager.com www.google-analytics.com ajax.googleapis.com"
//  val styles = "style-src 'self' cdnjs.cloudflare.com 'unsafe-inline'"
//  val fonts = "font-src 'self' cdnjs.cloudflare.com"
//  val images = "img-src 'self' https://*.googletagmanager.com www.googletagmanager.com https://*.google-analytics.com www.google-analytics.com"
//  val connect = "connect-src 'self' https://*.google-analytics.com https://*.analytics.google.com https://*.googletagmanager.com"
//  val filter = SecurityHeadersFilter(SecurityHeadersConfig(
//    frameOptions = None,
//    xssProtection = None,
//    contentSecurityPolicy = Option(default + "; " + javaScript + "; " + styles + "; " + fonts + "; " + images + "; " + connect),
//    contentTypeOptions = None
//  ))
//}
