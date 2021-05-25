#!/usr/bin/env bash

set -euxo pipefail

cmd="${1:-build}"

mkdir -p docs

rm -f website/sidebars.json
rm -rf docs/*

rm -f website/versions.json
rm -rf website/versioned_{docs,sidebars}
mkdir website/versioned_{docs,sidebars}

(cd website && yarn install)

# Generate scaladoc and mdoc from each submodule
(cd versions/2.x && sbt coreJVM/doc docs/mdoc)
(cd versions/3.x && sbt unidoc docs/mdoc)

mkdir -p website/static/api

mkdir -p website/static/api/2.x
rm -rf website/static/api/2.x/*
cp -R versions/2.x/core/jvm/target/scala-2.13/api/* website/static/api/2.x/

cp -R versions/2.x/site-docs/target/mdoc/* docs/
cp versions/2.x/site-docs/sidebars.json website/sidebars.json
(cd website && yarn run version 2.x)

mkdir -p website/static/api/3.x/
rm -rf website/static/api/3.x/*
cp -R versions/3.x/target/scala-2.13/unidoc/* website/static/api/3.x/

cp -R versions/3.x/site-docs/target/mdoc/* docs/
cp versions/3.x/site-docs/sidebars.json website/sidebars.json
(cd website && yarn run version 3.x)

cd website
if [[ "$cmd" == "host" ]]; then
  exec yarn start
else
  exec yarn build
fi
