#!/bin/bash
set -e

cd `dirname $0`/..

if [ -z "$MAIN_SCALA_VERSION" ]; then
    >&2 echo "Environment MAIN_SCALA_VERSION is not set. Check .travis.yml."
    exit 1
elif [ -z "$TRAVIS_SCALA_VERSION" ]; then
    >&2 echo "Environment TRAVIS_SCALA_VERSION is not set."
    exit 1
else
    echo
    echo "TRAVIS_SCALA_VERSION=$TRAVIS_SCALA_VERSION"
    echo "MAIN_SCALA_VERSION=$MAIN_SCALA_VERSION"
fi

if [ "$TRAVIS_SCALA_VERSION" = "$MAIN_SCALA_VERSION" ]; then
    echo
    echo "Executing build (with coverage):"
    echo
    sbt -Dsbt.profile=coverage ++$TRAVIS_SCALA_VERSION coverage ci
else
    echo
    echo "Executing build:"
    echo
    sbt ++$TRAVIS_SCALA_VERSION ci
fi