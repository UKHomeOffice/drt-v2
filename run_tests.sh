#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

npm --prefix client ci

sbt \
  scalafmtAll \
  coverage \
  "client/Test/test" \
  "server/Test/test" \
  coverageReport \
  coverageOff