#!/usr/bin/env bash

# This script tests to ensure that the crazy sbt detection logic in
# IOApp is actually working and correctly forwards cancelation on to
# the unforked subprocess

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

output=$(mktemp)

await-output() {
  local c=0
  until (cat $output | grep "$1" > /dev/null) || [[ $c -gt 20 ]]; do
    sleep 1
    c=$(($c+1))
  done

  if [[ $c -gt 20 ]]; then
    echo -e "\e[31mTimed out waiting for '$1' in output\e[0m"
    return -1
  else
    return 0
  fi
}

cd $(dirname $0)/..

sbt ++$1 'show scalaVersion'   # force launcher fetching
launcher=~/.sbt/launchers/$(cat project/build.properties | sed s/sbt.version=//)/sbt-launch.jar

echo -e "\e[32mRunning sbt...\e[0m"
java -jar $launcher ++$1 exampleJVM/run &> >(tee $output) &

await-output started
echo -e "\e[32mKilling sbt\e[0m"
kill %1
await-output canceled
kill %1 || :

rm $output
