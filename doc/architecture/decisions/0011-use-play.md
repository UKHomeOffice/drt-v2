# 11. use Play

Date: 31/03/2017

## Status

Accepted

## Context

We need a webserver. Obvious choices between spray (or akka-http) or Play. Given we're doing very little that uses the 
full power of play, I'd normally have gone with akka-http, but this reactjs tutorial started with Play, so we stuck with it 
for the spik 

## Decision

Play

## Consequences

Play is probably a heavier framework than we need. But as this began as a spike it was easier to stick with it. Also, the autowire
is wired into it. 