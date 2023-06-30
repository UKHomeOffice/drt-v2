package slickdb

import actors.PostgresTables
import slick.jdbc.PostgresProfile.api._
import slick.lifted.ProvenShape
import upickle.default.{macroRW, write}

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

import upickle.default._

case class TrainingDataTemplate(id: Option[Int], uploadTime: Timestamp, fileName: Option[String], title: Option[String], markdownContent: String)

class TrainingDataTemplateTable(tag: Tag) extends Table[TrainingDataTemplate](tag, "training_data_template") {
  def id: Rep[Option[Int]] = column[Option[Int]]("id", O.PrimaryKey, O.AutoInc)

  def uploadTime: Rep[Timestamp] = column[Timestamp]("upload_time")

  def fileName: Rep[Option[String]] = column[Option[String]]("file_name")

  def title: Rep[Option[String]] = column[Option[String]]("title")

  def markdownContent: Rep[String] = column[String]("markdown_content")

  def * : ProvenShape[TrainingDataTemplate] =
    (id, uploadTime, fileName, title, markdownContent).mapTo[TrainingDataTemplate]
}

sealed trait TrainingDataTemplateLike
{
  import upickle.default.{ReadWriter, macroRW}

  implicit val timestampReader: ReadWriter[java.sql.Timestamp] = readwriter[String].bimap[java.sql.Timestamp](
    timestamp => timestamp.getTime.toString,
    str => new Timestamp(str.toLong)
  )
  implicit val rw: ReadWriter[TrainingDataTemplate] = macroRW
}

object TrainingData extends TrainingDataTemplateLike {

  val trainingDataTemplates = TableQuery[TrainingDataTemplateTable]

  def getTrainingData()(implicit ec: ExecutionContext): Future[String] = {
    selectAll.map(write(_))
  }

  def selectAll: Future[Seq[TrainingDataTemplate]] = {
    val selectAction = trainingDataTemplates.sortBy(_.uploadTime.desc).result
    PostgresTables.db.run(selectAction)
  }

  def getFileId(filename:String)(implicit ec: ExecutionContext)  = {
    val selectAction = trainingDataTemplates.filter(_.fileName === filename).map(_.id).result
    val fileIds: Future[Seq[Option[Int]]] = PostgresTables.db.run(selectAction)
    fileIds.map(_.headOption.flatten)
  }


}
