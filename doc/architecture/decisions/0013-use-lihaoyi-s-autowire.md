# 10. use lihaoyi's autowire

Date: 31/06/2016

## Status

Accepted

## Context

We've got a Single Page app, it needs to talk to the server. Our use of scala and scalajs means we can use [lihaoyi's autowire
macros](https://github.com/lihaoyi/autowire)
Although this is essentially a 0 on the [Richardson maturity model](https://martinfowler.com/articles/richardsonMaturityModel.html)
it has huge benefits in terms of speed of change. We also (at the moment) only have the one client of the SPA so we can afford the tight coupling. 
It doesn't preclude moving toward something more restful, as we can just add routes when we recognise a need.

## Decision

Use autowire for now. 


## Consequences

+ \+ Compile time safety between front and back. 
- \- macro magic that has cause a few headaches here and there (beware immutable.Seq)
