package spatutorial.shared

case class CrunchResult(recommendedDesks: Seq[Int], waitTimes: Seq[Int])

trait Api {
  def welcomeMsg(name: String): String

  def getAllTodos(): Seq[TodoItem]

  def updateTodo(item: TodoItem): Seq[TodoItem]

  def deleteTodo(itemId: String): Seq[TodoItem]

  def getWorkloads(): Seq[Double]

  def crunch(workloads: Seq[Double]): CrunchResult

  def processWork(workloads: Seq[Double]): CrunchResult
}
