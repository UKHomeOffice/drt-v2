package controllers

import play.api.mvc._
import play.api.routing.sird._
import play.api.routing.{Router, SimpleRouter}
import router.Routes
import uk.gov.homeoffice.drt.testsystem.controllers.TestController
import javax.inject.{Inject, Singleton}

@Singleton
class TestRouter @Inject()(cc: ControllerComponents, testController: TestController) extends SimpleRouter {

  val router: Router = Router.from {

    case POST(p"/test/arrival") => testController.addArrival

    case POST(p"/test/arrivals/$forDate") => testController.addArrivals(forDate)

    case POST(p"/test/manifest") => testController.addManifest

    case POST(p"/test/mock-roles") => testController.setMockRoles

    case GET(p"/test/mock-roles-set") => testController.setMockRolesByQueryString

    case DELETE(p"/test/data") => testController.deleteAllData

  }


  override def routes = router.routes

}

@Singleton
class AppRouter @Inject()(defaultRoutes: Routes) extends SimpleRouter {
  def routes: Router.Routes = defaultRoutes.routes
}

@Singleton
class CombinedRouter @Inject()(defaultRoutes: Routes, testRouter: TestRouter) extends SimpleRouter {
  def routes: Router.Routes = defaultRoutes.routes.orElse(testRouter.routes)
}
