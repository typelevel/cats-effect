#!/usr/bin/env bash

set -euo pipefail # STRICT MODE
IFS=$'\n\t'       # http://redsymbol.net/articles/unofficial-bash-strict-mode/

for sjs in '1.0.0' '0.6.32'; do
  SCALAJS_VERSION=$sjs sbt clean +test
done
