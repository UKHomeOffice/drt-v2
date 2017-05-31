# 10. use akka persistence

Date: 31/03/2017

## Status

Accepted

## Context

We're storing state in actors (A flights actor, a staffAssignments actor), to decrease lag on startup, use akka persistence. 
We don't have a need (yet) for a database for the live system. 

## Decision

Use akka persistence with serialization 


## Consequences

Needed a message serialization approach See [0012-serialize-akka-persistence-with-protobuf.md](0012-serialize-akka-persistence-with-protobuf.md)
