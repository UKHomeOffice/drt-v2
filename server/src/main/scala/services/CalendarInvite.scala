package services

import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

object CalendarInvite {

  def generateICS(
                   start: ZonedDateTime,
                   end: ZonedDateTime,
                   summary: String,
                   description: String,
                   location: String,
                   organiserName: String,
                   organiserEmail: String,
                 ): String = {
    val dtFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    s"""BEGIN:VCALENDAR
       |VERSION:2.0
       |PRODID:-//Home Office//DRT//EN
       |BEGIN:VEVENT
       |UID:${java.util.UUID.randomUUID()}
       |DTSTAMP:${dtFormat.format(ZonedDateTime.now())}
       |ORGANIZER;CN=$organiserName:mailto:$organiserEmail
       |DTSTART:${dtFormat.format(start)}
       |DTEND:${dtFormat.format(end)}
       |SUMMARY:$summary
       |DESCRIPTION:$description
       |LOCATION:$location
       |END:VEVENT
       |END:VCALENDAR""".stripMargin
  }

  def invite(start: ZonedDateTime,
             end: ZonedDateTime,
             summary: String,
             description: String,
             location: String,
             organiserName:String,
             organiserEmail:String): String = {
    val icsContent = generateICS(
      start = start,
      end = end,
      summary = summary,
      description = description,
      location = location,
      organiserName = organiserName,
      organiserEmail = organiserEmail
    )
    icsContent
  }


}

