#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

if [[ $# -lt 2 ]] || [[ "$1" == "--help" ]]; then
  echo "usage: $0 old-tag new-tag"
  exit 1
fi

if [[ "$1" == "--base" ]]; then
  shift
  cd "$1"
  shift
else
  cd "$(dirname $0)/.."
fi

old_version="${1#v}"
new_version="${2#v}"

# perl is ironically more portable than sed because of GNU/BSD differences
# the quote reduce the false positive rate
find . -type f -name '*.md' -exec perl -pi -e "s/\"$old_version\"/\"$new_version\"/g" {} \;
find . -type f -name '*.md' -exec perl -pi -e "s/\\*\\*$old_version\\*\\*/\\*\\*$new_version\\*\\*/g" {} \;
find . -type f -name '*.md' -exec perl -pi -e "s/:$old_version/:$new_version/g" {} \;
