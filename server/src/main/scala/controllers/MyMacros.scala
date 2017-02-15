package controllers

import autowire.Core.Router
import autowire.Macros.MacroHelp

import scala.reflect.macros.Context

object MyMacros {

  def routeMacro[Trait, PickleType]
  (c: Context)
  (target: c.Expr[Trait])
  (implicit t: c.WeakTypeTag[Trait], pt: c.WeakTypeTag[PickleType])
  : c.Expr[Router[PickleType]] = {
    import c.universe._
    val help = new MacroHelp[c.type](c)
    val topClass = weakTypeOf[Trait]
    val routes = help.getAllRoutesForClass(pt, target, topClass, topClass.typeSymbol.fullName.toString.split('.').toSeq, Nil).toList

    val res = q"{case ..$routes}: autowire.Core.Router[$pt]"
    println(("FAFA", res))
    c.Expr(res)
  }
}
