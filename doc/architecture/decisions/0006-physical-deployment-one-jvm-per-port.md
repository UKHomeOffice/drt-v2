# 6. Physical deployment - one jvm per port

Date: 31/03/2017

## Status

Accepted

## Context

DRTv1 tried to normalise all ports into a single model. We frequently had requests for port specific features
or structures. Also it 

## Decision

Use a jvm per airport 

## Consequences

Reduces downtime on deployments, allows us to have port specific changes (such as a different pipeline for EDI)

