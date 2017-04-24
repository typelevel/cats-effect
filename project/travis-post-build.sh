#!/usr/bin/env bash

set -e

cd `dirname $0`/..

if [ -z "$MAIN_SCALA_VERSION" ]; then
    >&2 echo "Environment MAIN_SCALA_VERSION is not set. Check .travis.yml."
    exit 1
elif [ -z "$TRAVIS_SCALA_VERSION" ]; then
    >&2 echo "Environment TRAVIS_SCALA_VERSION is not set."
    exit 1
else
    echo "TRAVIS_SCALA_VERSION=$TRAVIS_SCALA_VERSION"
    echo "MAIN_SCALA_VERSION=$MAIN_SCALA_VERSION"
fi

if [ "$TRAVIS_SCALA_VERSION" = "$MAIN_SCALA_VERSION" ]; then
    echo "Uploading coverage for Scala $TRAVIS_SCALA_VERSION"
    sbt -Dsbt.profile=coverage ";++$TRAVIS_SCALA_VERSION;coverageAggregate;coverageReport"
    bash <(curl -s https://codecov.io/bash)
else
    echo "Skipping uploading coverage for Scala $TRAVIS_SCALA_VERSION"
fi

