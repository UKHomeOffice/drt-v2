// import moment from "moment-timezone";
const moment = require('moment-timezone');
moment.locale("en-gb");

const todayAtUtcString = (hours: number, minutes: number): string =>
    moment()
        .utc()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .format()

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
    todayAtUtcString,
    inDaysAtTimeUtcString,
    currentTimeString,
}
