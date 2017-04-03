# 9. use akka streams

Date: 31/03/2017

## Status

Accepted

## Context

The bulk of the application is a processing pipeline for data feeds. We need a way to process streams of information.
We considered apache-spark but we didn't think we  have enough data yet to justify the overhead of a spark installation. 
Considered Java/ScalaRX, but we're not quite reactive - also due to the processing overhead of the crunch, the backpressure which 
you can get with Akka Streams seemed like a win

## Decision

Use akka streams

## Consequences

We get typed parallelisable processing of the things we are consuming. 
