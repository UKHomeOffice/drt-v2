package actors

import actors.restore.RestorerWithLegacy
import drt.shared.{WithLegacyUniqueId, WithUnique}
import org.specs2.mutable.Specification

import scala.collection.mutable

class RestorerWithLegacySpec extends Specification {
  def newRestorer = new RestorerWithLegacy[Int, MyIndex, MyItem]

  private val item1 = new MyItem("1")
  private val item2 = new MyItem("2")
  private val item3 = new MyItem("3")
  private val item4 = new MyItem("4")

  "Given one update and no removals " +
    "The state after tidyUp() should have an empty legacyMap and the one item" >> {
    val restorer = newRestorer

    restorer.update(Seq(item1))

    restorer.finish()

    restorer.legacyMap.isEmpty === true && restorer.items === mutable.Map(item1.unique -> item1)
  }

  "Given updates containing item1 & item2 and one legacy removal for item1" +
    "The state after tidyUp() should have an empty legacyMap and just item2" >> {
    val items = Seq(item1, item2)
    val restorer = newRestorer

    restorer.update(items)
    restorer.removeLegacies(Seq(item1.unique.uniqueId))

    restorer.finish()

    restorer.legacyMap.isEmpty === true && restorer.items === mutable.Map(item2.unique -> item2)
  }

  "Given updates containing items 1 through 4, followed by a legacy removal for item 1 and regular removal for item 2 " +
    "The state after tidyUp() should have an empty legacyMap and items 3 & 4" >> {
    val items = Seq(item1, item2, item3, item4)
    val restorer = newRestorer

    restorer.update(items)
    restorer.removeLegacies(Seq(item1.unique.uniqueId))
    restorer.remove(Seq(item2.unique))

    restorer.finish()

    restorer.legacyMap.isEmpty === true && restorer.items === mutable.Map(item3.unique -> item3, item4.unique -> item4)
  }
}

class MyIndex(val name: String) extends WithLegacyUniqueId[Int, MyIndex] {
  override def uniqueId: Int = hashCode()

  override def compare(that: MyIndex): Int = this.name.compare(that.name)
}

class MyItem(name: String) extends WithUnique[MyIndex] {
  override val unique: MyIndex = new MyIndex(name)
}
