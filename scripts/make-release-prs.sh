#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

cd "$(dirname $0)/.."
primary_base="$(pwd)"

if [[ $# -ne 2 ]] || [[ "$1" == "--help" ]]; then
  echo "usage: $0 old-version new-version"
  exit 1
fi

old_version="$1"
new_version="$2"

minor_base=series/$(echo $new_version | sed -E 's/([0-9]+).([0-9]+).[0-9]+/\1.\2.x/')
major_base=series/$(echo $new_version | sed -E 's/([0-9]+).[0-9]+.[0-9]+/\1.x/')
branch="release/$new_version-minor"

cd "$(mktemp -d)"
git clone git@github.com:typelevel/cats-effect.git
cd 'cats-effect'

git checkout -b $branch origin/$minor_base
"$primary_base/scripts/update-versions.sh" --base . $old_version $new_version
git commit -a -m "Update versions for $new_version"
git push origin $branch

gh pr create \
  --fill \
  --base $minor_base \
  --repo typelevel/cats-effect \
  --head typelevel:$branch \
  --label ':robot:'

gh pr create \
  --title "Merge changes from $new_version into $major_base" \
  --body '' \
  --base $major_base \
  --repo typelevel/cats-effect \
  --head typelevel:$branch \
  --label ':robot:'
