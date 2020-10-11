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
(cd versions/2.x && sbt clean doc docs/mdoc)
(cd versions/3.x && sbt clean coreJVM/doc docs/mdoc)

# Create Docusaurus directories
mkdir -p ./website/versioned_sidebars
mkdir -p ./website/versioned_docs

mv ./versions/2.x/site-docs/target/mdoc ./docs
cp ./versions/2.x/site-docs/sidebars.json ./website/sidebars.json
(cd website && yarn run version 2.x)


rm -rf ./website/sidebars.json
rm -rf ./docs
mv ./versions/3.x/site-docs/target/mdoc ./docs
cp ./versions/3.x/site-docs/sidebars.json ./website/sidebars.json
(cd website && yarn run version 3.x)

if [[ "$cmd" == "host" ]]; then
  (cd website && yarn start)
else
  (cd website && yarn build)

  mv ./versions/2.x/core/jvm/target/scala-2.13/api ./website/build/cats-effect/
  mkdir -p ./website/build/cats-effect/3.x/
  mv ./versions/3.x/core/jvm/target/scala-2.13/api ./website/build/cats-effect/3.x
fi



