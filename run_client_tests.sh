#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

npm --prefix client ci

sbt "client/test"


