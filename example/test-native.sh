#!/usr/bin/env bash

# This script mostly just ensures that we can use native to run an example application.

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

cd $(dirname $0)/..

sbt ++$1 exampleNative/nativeLink

output=$(mktemp)
expected=$(mktemp)

cd example/native/target/scala-$(echo $1 | sed -E 's/^(2\.[0-9]+)\.[0-9]+$/\1/')/

set +e
./cats-effect-example left right > $output
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
