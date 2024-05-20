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

cd "$(mktemp -d)"
gh repo clone typelevel/cats-effect
cd 'cats-effect'

git checkout origin/docs
git submodule init

branch=release/$new_version-site
git checkout -b $branch

scastie_scala_string="$(jq -R -s '.' < "${primary_base}/scripts/data/scastie.scala")"

scastie_sbt_full="$(cat << SBT
$(cat "${primary_base}/scripts/data/scastie.sbt")

libraryDependencies += "org.typelevel" %% "cats-effect" % "$new_version"
SBT
)"

scastie_sbt_string="$(echo "$scastie_sbt_full" | jq -R -s '.')"

post_file="$(mktemp)"
cat > "$post_file" << JSON
{
  "_isWorksheetMode": false,
  "code": $scastie_scala_string,
  "isShowingInUserProfile": false,
  "libraries": [],
  "librariesFromList": [],
  "sbtConfigExtra": $scastie_sbt_string,
  "sbtPluginsConfigExtra": "",
  "target": {
    "scalaVersion": "2.13.10",
    "tpe": "Jvm"
  }
}
JSON

# echo "$post_body" | jq

uuid=$(curl \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "@$post_file" \
  https://scastie.scala-lang.org/api/save | jq -r .base64UUID)

perl -pi -e "s/\"$old_version\"/\"$new_version\"/" website/pages/en/index.js
perl -pi -e "s|https://scastie.scala-lang.org/[^\"]+|https://scastie.scala-lang.org/$uuid|" website/pages/en/index.js

git commit -a -m "Updated site for $new_version"
git push origin $branch

gh pr create \
  --fill \
  --base docs \
  --repo typelevel/cats-effect \
  --head typelevel:$branch \
  --label ':robot:'
