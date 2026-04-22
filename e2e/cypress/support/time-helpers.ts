// import moment from "moment-timezone";
const moment = require('moment-timezone');
moment.locale("en-gb");

const portTimeZone = 'Europe/London'

const todayAtPortLocal = (hours: number, minutes: number): moment.Moment =>
    moment()
        .tz(portTimeZone)
        .startOf('day')
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .milliseconds(0)

// Legacy helper retained for backwards compatibility with existing tests.
const todayAsLocalString = (hours: number, minutes: number): string =>
    moment()
        .utc()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .format()

const todayAsUtcString = (hours: number, minutes: number): string => {
    return todayAtPortLocal(hours, minutes)
      .clone()
      .utc()
      .format('YYYY-MM-DDTHH:mm:ss[Z]')
}

const inDaysAtTimeUtcString = (daysToAdd: number, hours: number, minutes: number): string =>
    moment()
        .utc()
        .startOf('day')
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .milliseconds(0)
        .add(daysToAdd, 'days')
        .format('YYYY-MM-DDTHH:mm:ss[Z]')

const todayAtUtc = (hours: number, minutes: number): moment.Moment =>
    moment()
        .utc()
        .startOf('day')
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .milliseconds(0)


const currentTimeString = (): string =>
    moment()
        .utc()
        .seconds(0)
        .milliseconds(0)
        .format('YYYY-MM-DDTHH:mm:ss[Z]')

export {
    moment,
    todayAtUtc,
    todayAtPortLocal,
    todayAsLocalString,
    inDaysAtTimeUtcString,
    currentTimeString,
    todayAsUtcString,
}
