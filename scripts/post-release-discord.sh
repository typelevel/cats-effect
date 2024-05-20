#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

if [[ $# -ne 1 ]] || [[ "$1" == "--help" ]]; then
  echo "usage: $0 new-tag"
  exit 1
fi

# defunct example:
#
# export DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/1122248098014564483/qv_hfoMpexcTjX_8FX23uisvpqrt_N_UD8VtYFLzUo8ROthEWk5cqECQPB3OCJ9MNUxB'
#
# this url should be considered a secret and handled with appropriate care

data="{\"content\":\"https://github.com/typelevel/cats-effect/releases/tag/$1\"}"

exec curl -H "Content-Type: application/json" -X POST -d "$data" "$DISCORD_WEBHOOK_URL"
