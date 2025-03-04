# DRT Dynamic Response Tool


This project is a tool used by UK Border Force to help ensure sufficient resources are available at the border to deal
with anticipated demand.
It is the latest iteration of a tool to tackle this lofty goal. This repo replaces an earlier, PHP and Scala set of projects.
We began from the ground up here, consolidating back into a monolith per port. 

## High level data flow
![image info](./doc/architecture/diagrams/DRT%20High%20Level%20Overview.svg)

## DRT main app data flow
![image info](./doc/architecture/diagrams/DRT%20v2%20data%20flow%20-%202021.svg)

Drt v2 is a replacement for an earlier system built primarily with PHP.

It is a tool which aims to provide Border Force officers in airports with information that helps them
A) respond to live changes to circumstances - flights coming in early or late, lower than expected resourcing due to people calling in sick,
B) planning for future resourcing


## Approach
The core of DRT v2 is an algorithm by HOAI. It's an optimization function which searches a space for the lowest cost based on: queue length (time), number of desks staffed, cost of changing staff and so on.
The underlying algorithm is based on NM1Q simulation.

### Recognized flaws
NM1 is based on the assumption that the processing times are normally distributed. In practice, we do not have a normal distribution. We've got a multi-modal distribution with a very long tail.
That is, we have a mean processing time of something like 40s, but we know different countries have different averages. Also, if there is a problem with someone's status, then that can take many minutes of an officers time, and may close a desk for 10-20 minutes, depending on the port.

An assumption/desire to operate with high utilization. Littles law shows us this is problematic.

## Feeds

### Airports/Flights
We take feeds from the Airports about flight statuses. Ports use a variety of formats including JSON. LHR has their own proprietary feed based on CSV files on a web server. Gatwick use a push service in Microsoft's Azure cloud.

### API (Advance Passenger Info)
This provides us data about the nationality breakdown on incoming flights.

### Technical Stack
Scala server. Scalajs reactjs client. Communication between client server is via lihaoyi's autowire & spray JSON. Server is hosted in play, uses Akka streams to read the feeds, state is store in Akka actors. Some of the actors use akka persistence.
The simulation/optimization algorithm is Scala, translated & enhanced from R taken directly from HOAI.

### Physical Deployment
Each port has their own instance. The feeds are chosen at application start based on envvars

![System Flow](doc/architecture/diagrams/systemflow.svg?raw=true)


### Setting up Postgres locally

```
## Linux
To enable non-system account logins you'll need to edit some configuration:

sudo vim /etc/postgresql/12/main/pg_hba.conf

Change the following line;

local   all             all                                     peer
to
local   all             all                                     md5


To access postgress before you've set up any additional accounts you'll need to use it like this;

sudo -u postgres psql

The following commands also have to be prefixed with 'sudo -u postgres';

createuser ltn
createdb -O ltn ltn
psql -U ltn -W -h localhost ltn
# you may need to set the password for the user, which you can do by logging into posgres and running:
alter user ltn with password 'ltn';

createuser drt
createdb -O drt aggregated
psql -U drt -W -h localhost aggregated

# you may need to set the password for the user, which you can do by logging into posgres and running:
alter user drt with password 'drt';
```
You can create the relevant tables for the akka db using the akka-persistence-postgres.sql file in the resources folder and for the aggregated db using aggregated-arrivals.sql

### NB - duplicate key insertion errors
We've experienced the `nextval` for the ordering column become out of sync with the actual values. This results in permanent insertion errors until it's fixed
To reset the value, first delete the deployment for the port in question to make sure there are no insertions going on (or lock the table as appropriate). Then issue the following SQL command:

```SELECT setval('journal_ordering_seq', COALESCE((SELECT MAX(ordering)+1 FROM journal), 1), false);```

This gets the values back in sync and resolves the duplicate key insertion errors. The mystery is how we ended up having them out of sync in the first place...

#Updating the akka version and akka persistent jdbc 
With upgrade for akka version (2.6.17) and akka persistent jdbc (5.0.4) there is change in schema for journal and snapshot . At moment there are no tools to migration from legacy to new schema.
But there is option provided to continue using legacy schema with some configure update for dao which is document by lightbend  https://doc.akka.io/docs/akka-persistence-jdbc/current/migration.html.
At some point once the migration tool is available we can migrate to new schema which is beneficial.

### Running NVD mirror locally
 Need docker installed and running locally. 
 Following command will pull the docker image and run it locally on port 8008
 ```docker run -d --name nvdmirror-instance -p 8008:80 sspringett/nvdmirror```

## API version 1
### Flights

Returns JSON object containing a list of ports. Each port contains a list of terminals. Each terminal contains a list of flights.
Flights have fields:
- code: Flight code standardised to 4 numeric digits padded with leading zeros, eg "BA0417"
- scheduledTime: Scheduled landing slot time, ISO format, eg "2024-11-13T11:40:00Z"
- originPortIata: Origin port's 3 letter IATA code, eg "LUX"
- originPortName: Origin port's name, "Luxembourg"
- estimatedPcpPaxCount: Optional. Estimated number of passengers that will cross the boarder (excludes transit passengers at LHR), eg 47
- estimatedLandingTime: Optional. Updated estimation of landing/touchdown time for the plane, ISO format, eg "2024-11-13T11:12:00Z"
- actualChocksTime: Optional. Time the plane pulled up at chocks when available. ISO format, eg "2024-11-13T11:12:00Z"
- estimatedPcpStartTime: The estimated time the first passengers will reach the back of the PCP queue. ISO format, eg "2024-11-13T11:30:00Z"
- estimatedPcpEndTime: The estimated time the last passengers will reach the back of the PCP queue. ISO format, eg "2024-11-13T11:33:00Z"
- status: Standardised status. Possible values are:
  - Scheduled - usually indicates the flight has not yet taken off
  - Expected - updated timing information is available 
  - Delayed - the flight is expected to be delayed by at least 15 minutes
  - Landed - the flight has landed
  - On Chocks - the flight has landed and is at the gate
  - Diverted (rarely available) - the flight has been diverted

### Queues
* incomingPax: The number of passengers expected to join the back of the queue during the time slot. Fast-track will always be zero for the time being
* maxWaitMinutes: The maximum wait time of any passengers processed during the time slot
* queue: The type of queue:
  * eeadesk
  * noneeadesk
  * egate
  * fasttrack

### Ports & terminals
* ABZ: T1
* API: T1
* BFS: T1
* BHD: T1
* BHX: T1, T2
* BOH: T1
* BRS: T1
* CWL: T1
* EDI: A1, A2
* EMA: T1
* EXT: T1
* GLA: T1
* HUY: T1
* INV: T1
* LBA: T1
* LCY: T1
* LGW: N, S
* LHR: T2, T3, T4, T4
* LPL: T1
* LTN: T1
* MAN: T1, T2, T3
* MME: T1
* NCL: T1
* NQY: T1
* NWI: T1
* PIK: T1
* SEN: T1
* SOU: T1
* STN: T1
