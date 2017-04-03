# 12. Serialize akka persistence with Protobuf

Date: 31/03/2017

## Status

Accepted

## Context

For akka persistence to be resilient to message schema changes you can't/shouldn't rely on java  serialization. 
The big 3 serialization approaches would be: 
json
protobuf
thrift

http://blog.codeclimate.com/blog/2014/06/05/choose-protocol-buffers/

## Decision

Use google's protobuf for serialization

## Consequences

Gives us a layer / seam where we'll be able to migrate messages. Brings in another stage to the sbt build. 