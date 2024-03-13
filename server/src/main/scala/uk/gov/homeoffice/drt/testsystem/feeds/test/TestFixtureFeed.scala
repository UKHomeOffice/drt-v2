package uk.gov.homeoffice.drt.testsystem.feeds.test

import uk.gov.homeoffice.drt.arrivals.Arrival

import scala.language.postfixOps


case object GetArrivals

case class Arrivals(arrivals: List[Arrival])
