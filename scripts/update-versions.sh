#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

cd "$(dirname $0)/.."

old_version="$1"
new_version="$2"

# perl is ironically more portable than sed because of GNU/BSD differences
# the quote reduce the false positive rate
find . -type f -name '*.md' -exec perl -pi -e "s/\"$old_version\"/\"$new_version\"/g" {} \;
