import moment from 'moment-timezone';
moment.locale("en-gb");

const todayAtUtcString = (hours: number, minutes: number): moment =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .utc()
        .format()

const inDaysAtTimeUtcString = (daysToAdd: number, hours: number, minutes: number): moment =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .add(daysToAdd, 'days')
        .utc()
        .format()

const todayAtUtc = (hours: number, minutes: number): moment =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .utc()

const currentTimeString = (): moment =>
    moment()
        .seconds(0)
        .utc()
        .format()

export {
    todayAtUtc,
    todayAtUtcString,
    inDaysAtTimeUtcString,
    currentTimeString,
}
