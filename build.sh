#!/usr/bin/env bash

set -euxo pipefail

cmd="${1:-build}"

rm -rf ./website/versions.json
rm -rf ./website/versioned_sidebars
rm -rf ./website/versioned_docs
rm -rf ./website/sidebars.json
rm -rf ./docs

(cd website && yarn install)

# Generate scaladoc and mdoc from each submodule
(cd versions/2.x && sbt clean coreJVM/doc docs/mdoc)
(cd versions/3.x && sbt clean unidoc docs/mdoc)

# Create Docusaurus directories
mkdir -p ./website/versioned_sidebars
mkdir -p ./website/versioned_docs

rm -rf ./website/sidebars.json
rm -rf ./docs

mkdir -p ./website/static/api

rm -rf ./website/static/api/2.x
mv ./versions/2.x/core/jvm/target/scala-2.13/api ./website/static/api/2.x

mv ./versions/2.x/site-docs/target/mdoc ./docs
cp ./versions/2.x/site-docs/sidebars.json ./website/sidebars.json
(cd website && yarn run version 2.x)
rm -rf ./docs

rm -rf ./website/static/api/3.x
mv ./versions/3.x/target/scala-2.13/unidoc ./website/static/api/3.x

mv ./versions/3.x/site-docs/target/mdoc ./docs
cp ./versions/3.x/site-docs/sidebars.json ./website/sidebars.json
(cd website && yarn run version 3.x)

cd website
if [[ "$cmd" == "host" ]]; then
  exec yarn start
else
  exec yarn build
fi
