# 7. Use Renjin

Date: 31/03/2017

## Status

Accepted

## Context

The optimiser which calculates optimal numbers of open desks based on the workload it's given was originally 
written by Home Office Science in R. 
In DRT v1 that optimiser algorithm was implemented in PHP. It is quite slow. 
Given that the algo assumes a normal distribution of processing times, and BF recognises that we don't have that in reality, we 
will likely eventually replace the optimizer with a Montecarlo simulation so we can better deal with the stochastic elements. 

## Decision

Use Renjin to run the optimizer algo directly

## Consequences

It's much faster than the PHP algo. We don't have to translate the R into scala. 
