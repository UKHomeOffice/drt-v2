
package drt.server.feeds.lhr.forecast

import java.io._
import javax.mail._
import javax.mail.internet.InternetAddress
import javax.mail.search.FromTerm

import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

case class LHRForecastEmail(mailHost: String,
                            userName: String,
                            userPassword: String,
                            from: String,
                            mailPort: Int = 993) {

  val log = LoggerFactory.getLogger(getClass)

  def hasXlsAttachment(m: Message) = m.getContent match {
    case mp: Multipart =>
      (0 until mp.getCount)
        .map(p => mp.getBodyPart(p)).exists(p => {
        Option(p.getFileName).exists(f => f.contains(".xlsx"))
      })
    case _ => false
  }

  def tryConnectToInbox(store: Store): Option[Folder] = Try {
    log.info(s"Connecting to mail server $mailHost")
    store.connect(mailHost, mailPort, userName, userPassword)
    store.getFolder("Inbox")
  } match {
    case Success(s: Folder) =>
      log.info(s"Successfully found imap folder $mailHost")

      Some(s)
    case Failure(f: Throwable) =>
      log.error(s"Failed to connect to imap server ${f.getMessage}")
      None
    case _ => None
  }

  def imapStore(): Store = {
    val props = System.getProperties
    props.setProperty("mail.store.protocol", "imaps")
    props.setProperty("mail.imap.connectiontimeout", "60000")
    props.setProperty("mail.imap.timeout", "60000")
    props.setProperty("mail.imap.connectionpoolsize", "2")
    props.setProperty("mail.imap.connectionpooltimeout", "65000")
    props.setProperty("mail.imap.ssl.trust", "*")
    props.setProperty("mail.imaps.ssl.trust", "*")
    Session.getDefaultInstance(props, null).getStore("imaps")
  }

  def maybeLatestForecastFile: Option[File] = {

    val store = imapStore()
    val maybeFolder = tryConnectToInbox(store)
    val forecastAttachmentOption = maybeFolder.flatMap(inbox => {
      inbox.open(Folder.READ_ONLY)
      log.info(s"Looking for most recent forecast email.")

      val mostRecentAttachmentOption = inbox
        .search(new FromTerm(new InternetAddress("heathrow.com")))
        .reverse
        .collectFirst {
          case m: Message if hasXlsAttachment(m) =>
            m.getContent match {
              case mp: Multipart =>
                (0 until mp.getCount)
                  .map(p => mp.getBodyPart(p))
                  .find(p => Option(p.getFileName).exists(f => f.contains(".xlsx")))
                  .map(bp => {
                    val tempFile = File.createTempFile("LHR_Forecast", ".xlsx")
                    val outputStream = new FileOutputStream(tempFile)
                    bp.getDataHandler.writeTo(outputStream)
                    outputStream.close()
                    tempFile
                  })
            }
        }.flatten

      inbox.close(true)

      mostRecentAttachmentOption
    })
    store.close()

    forecastAttachmentOption
  }

}
