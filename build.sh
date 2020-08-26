#!/bin/bash

(cd website && yarn install)

# Generate scaladoc and mdoc from each submodule
(cd versions/2.x && sbt "; doc; docs/mdoc")

# Create Docusaurus directories
mkdir -p docs
mkdir -p ./website/versioned_sidebars
mkdir -p ./website/versioned_docs

cp -R ./versions/2.x/site-docs/target/mdoc/ ./docs
cp ./versions/2.x/site-docs/sidebars.json ./website/sidebars.json
(cd website && yarn run version 2.x)

(cd website && yarn build)

mv ./versions/2.x/core/jvm/target/scala-2.13/api ./website/build/cats-effect/
