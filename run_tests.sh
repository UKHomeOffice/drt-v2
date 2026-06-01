#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

npm --prefix client ci
sbt clean scalafmtAll compile coverage test coverageOff coverageReport dependencyUpdates

