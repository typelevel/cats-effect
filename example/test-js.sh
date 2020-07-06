#!/usr/bin/env bash

# This script mostly just ensures that we can use node to run an example application.

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

cd $(dirname $0)/..

sbt ++$1 exampleJS/compile

output=$(mktemp)
expected=$(mktemp)

cd example/js/target/scala-${1:0:4}/
node cats-effect-example-fastopt.js > $output

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
