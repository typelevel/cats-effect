#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

cd "$(dirname $0)/.."
primary_base="$(pwd)"

if [[ $# -ne 2 ]] || [[ "$1" == "--help" ]]; then
  echo "usage: $0 old-tag new-tag"
  exit 1
fi

old_version="${1#v}"
new_version="${2#v}"

minor_base=series/$(echo $new_version | sed -E 's/([0-9]+).([0-9]+).[0-9]+/\1.\2.x/')
major_base=series/$(echo $new_version | sed -E 's/([0-9]+).[0-9]+.[0-9]+/\1.x/')
minor_branch="release/$new_version-minor"
major_branch="release/$new_version-major"

cd "$(mktemp -d)"
gh repo clone typelevel/cats-effect
cd 'cats-effect'

git checkout -b $minor_branch origin/$minor_base
"$primary_base/scripts/update-versions.sh" --base . $old_version $new_version
git commit -a -m "Update versions for $new_version"
git push origin $minor_branch

gh pr create \
  --fill \
  --base $minor_base \
  --repo typelevel/cats-effect \
  --head typelevel:$minor_branch \
  --label ':robot:'

git checkout -b $major_branch
git push origin $major_branch

gh pr create \
  --title "Merge changes from $new_version into $major_base" \
  --body '' \
  --base $major_base \
  --repo typelevel/cats-effect \
  --head typelevel:$major_branch \
  --label ':robot:'
