#!/usr/bin/env bash

set -euxo pipefail

function check_dependency() {
  if ! command -v "$1" &> /dev/null; then
    echo "ERROR: $1 is required but not found in PATH"
    echo "Common fix:"
    echo "  - For Debian/Ubuntu: apt-get install $2"
    echo "  - For macOS: brew install $2"
    echo "  - For NixOS: Add $2 to your development environment"
    exit 1
  fi
}

check_dependency "gifsicle" "gifsicle"
check_dependency "autoreconf" "autoconf"
check_dependency "npm" "nodejs"
check_dependency "sbt" "sbt"

cmd="${1:-build}"

mkdir -p docs

rm -f website/sidebars.json
rm -rf docs/*

rm -f website/versions.json
rm -rf website/versioned_{docs,sidebars}
mkdir website/versioned_{docs,sidebars}

(cd website && npm install)

# Generate scaladoc and mdoc from each submodule
(cd versions/2.x && sbt coreJVM/doc docs/mdoc)
(cd versions/3.x && sbt unidoc docs/mdoc)

mkdir -p website/static/api

mkdir -p website/static/api/2.x
rm -rf website/static/api/2.x/*
cp -R versions/2.x/core/jvm/target/scala-2.13/api/* website/static/api/2.x/

cp -R versions/2.x/site-docs/target/mdoc/* docs/
cp versions/2.x/site-docs/sidebars.json website/sidebars.json
(cd website && npm run version 2.x)

mkdir -p website/static/api/3.x/
rm -rf website/static/api/3.x/*
cp -R versions/3.x/target/scala-2.13/unidoc/* website/static/api/3.x/

cp -R versions/3.x/site-docs/target/mdoc/* docs/
cp versions/3.x/site-docs/sidebars.json website/sidebars.json
(cd website && npm run version 3.x)

cd website
if [[ "$cmd" == "host" ]]; then
  exec npm run start
else
  exec npm run build
fi
