#!/bin/sh
set -e
sbt clean
sbt test docker:stage
