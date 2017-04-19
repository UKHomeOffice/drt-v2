# DRT Dynamic Response Tool


This project is a tool used by UK Borderforce to help ensure sufficient resources are available at the border to deal 
with anticipated demand. 
It is the latest (as of Mar 2017) iteration of a tool to tackle this lofty goal. This repo replaces an earlier, PHP, database
and Scala set of projects. 
We began from the ground up here, consolidating back into a monolith per port. We'll cover the decisions in more detail in 
[Architectural Decision Records](doc/architecture/decisions/0001-record-architecture-decisions.md)

Drt v2 is a replacement for an earlier system built primarily with PHP. 

It is a tool which aims to provide Borderforce officers in airports with information that helps them 
A) respond to live changes to circumstances - flights coming in early or late, lower than expected resourcing due to people calling in sick, 
B) planning for future resourcing

As of 2017-03-39 it primarily focuses on the 'live' aspect. 

## Approach
The core of both drt v1 and v2 is an algorithm by Home Office Science. It's an optimization function which searches a space for the lowest cost based on: queue length (time), number of desks staffed, cost of changing staff and so on. 
The underlying algorithm is based on NM1Q simulation. 

### Recognized flaws
NM1 is based on the assumption that the processing times are normally distributed. In practice, we do not have a normal distribution. We've got a multi-modal distribution with a very long tail. 
That is, we have a mean processing time of something like 40s, but we know different countries have different averages. Also, if there is a problem with someone's status, then that can take many minutes of an officers time, and may close a desk for 10-20 minutes, depending on the port. 

An assumption/desire to operate with high utilization. Littles law shows us this is problematic.

## Feeds

### Airports/Flights
We take feeds from the Airports about flight statuses. Most ports use a Json based web service called Chroma, there's a couple of different flavours of that. LHR has their own proprietary feed based on CSV files on a web server. Gatwick use a push service in Microsoft's Azure cloud - we've not wired this one into the new system yet.

### API (Advance Passenger Info)
This provides us data about the nationality breakdown on incoming flights. 

### Technical Stack
Scala server. Scalajs reactjs client. Communication between client server is primarily via lihaoyi's autowire. Server is hosted in play, uses Akka streams to read the feeds, state is store in Akka actors. Some of the actors use akka persistence. 
The simulation/optimization algorithm is R, taken directly from Home Office Science. 

### Physical Deployment
Each port has their own instance. The feeds are chose at application start based on envvars

![System Flow](doc/architecture/diagrams/systemflow.svg?raw=true)
