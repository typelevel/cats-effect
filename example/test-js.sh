#!/usr/bin/env bash

# This script mostly just ensures that we can use node to run an example application.

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

cd $(dirname $0)/..

sbt ++$1 exampleJS/fastOptJS

output=$(mktemp)
expected=$(mktemp)

cd example/js/target/scala-$(echo $1 | sed -E 's/\.[0-9]+$//')/

set +e
node cats-effect-example-fastopt.js left right > $output
result=$?
set -e

if [[ $result -ne 2 ]]; then
  exit 1
fi

echo $'left
left
left
left
left
right
right
right
right
right
left
left
left
left
left
right
right
right
right
right' > $expected

exec diff $output $expected
