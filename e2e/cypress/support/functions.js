let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

const todayAtUtcString = (hours, minutes) =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .utc()
        .format()

const inDaysAtTimeUtcString = (daysToAdd, hours, minutes) =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .add(daysToAdd, 'days')
        .utc()
        .format()

const todayAtUtc = (hours, minutes) =>
    moment()
        .hour(hours)
        .minute(minutes)
        .seconds(0)
        .utc()

exports.todayAtUtc = todayAtUtc

exports.todayAtUtcString = todayAtUtcString

exports.inDaysAtTimeUtcString = inDaysAtTimeUtcString
