# 8. Bring Advance passenger Info code into main service

Date: 31/03/2017

## Status

Accepted

## Context

When we began the Advance Passenger Info (API) work, we had been going to stand it up as a webservice, so that 
it could provide values to both PHP drt and drtv2. So it was a separate project. With the decision to focus on v2, we don't need that separation

## Decision

Bring the API service codebase into the main project

## Consequences

It's easier to grow it/change it as we learn more about the Port's requirements for API.
We need to poll the 's3' atmos bucket from each airport service. 

