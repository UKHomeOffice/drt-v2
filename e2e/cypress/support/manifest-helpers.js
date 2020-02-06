
const passengerProfiles = {

    ukPassport: {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": 30,
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "Passport",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    },

    ukChild: {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": 11,
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "Passport",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    },

    visaNational: {
        "DocumentIssuingCountryCode": "ZWE",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": 30,
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "2",
        "NationalityCountryCode": "ZWE"
    },

    nonVisaNational: {
        "DocumentIssuingCountryCode": "MRU",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": 30,
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "3",
        "NationalityCountryCode": "MRU"
    },

    b5JNational: {
        "DocumentIssuingCountryCode": "AUS",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": 30,
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "3",
        "NationalityCountryCode": "AUS"
    }
}


function manifestForDateTime(dateString, timeString, passengerList) {
    return {
        "EventCode": "DC",
        "DeparturePortCode": "AMS",
        "VoyageNumberTrailingLetter": "",
        "ArrivalPortCode": "TST",
        "DeparturePortCountryCode": "MAR",
        "VoyageNumber": "0123",
        "VoyageKey": "key",
        "ScheduledDateOfDeparture": dateString,
        "ScheduledDateOfArrival": dateString,
        "CarrierType": "AIR",
        "CarrierCode": "TS",
        "ScheduledTimeOfDeparture": "06:30:00",
        "ScheduledTimeOfArrival": timeString,
        "FileId": "fileID",
        "PassengerList": passengerList
    }
};


function passengerList(euPax, visaNationals, nonVisaNationals, b5JNationals) {

    return Array(euPax).fill(passengerProfiles.ukPassport)
        .concat(Array(visaNationals).fill(passengerProfiles.visaNational))
        .concat(Array(nonVisaNationals).fill(passengerProfiles.nonVisaNational))
        .concat(Array(b5JNationals).fill(passengerProfiles.b5JNational))
};


exports.manifestForDateTime = manifestForDateTime;

exports.passengerList = passengerList;

exports.passengerProfiles = passengerProfiles;
