// import moment from "moment-timezone";
const moment = require('moment-timezone');
moment.locale("en-gb");

const todayAsLocalString = (hours: number, minutes: number): string =>
    moment()
        .utc()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .format()

const todayAsUtcString = (hours: number, minutes: number): string => {
    const utc = moment()
      .hour(hours)
      .minute(minutes)
      .seconds(0)
      .toDate()

    return moment(utc)
      .utc()
      .format('YYYY-MM-DDTHH:mm:ss')
}

const inDaysAtTimeUtcString = (daysToAdd: number, hours: number, minutes: number): string =>
    moment()
        .utc()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .add(daysToAdd, 'days')
        .format()

const todayAtUtc = (hours: number, minutes: number): moment.Moment =>
    moment()
        .utc()
        .hour(hours)
        .minute(minutes)
        .seconds(0)


const currentTimeString = (): string =>
    moment()
        .utc()
        .seconds(0)
        .format()

export {
    moment,
    todayAtUtc,
    todayAsLocalString,
    inDaysAtTimeUtcString,
    currentTimeString,
    todayAsUtcString,
}
