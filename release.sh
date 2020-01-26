#!/usr/bin/env bash

set -euo pipefail # STRICT MODE
IFS=$'\n\t'       # http://redsymbol.net/articles/unofficial-bash-strict-mode/

for sjs in '1.0.0-RC2' '0.6.31'; do
  SCALAJS_VERSION=$sjs sbt +publish
done

sbt sonatypeReleaseAll microsite/publishMicrosite
