#!/usr/bin/env bash
# Creates, rotates, revokes or hands over an Owner's bearer token.
#
#   ./token.sh <owner>            create the token if missing, print it
#   ./token.sh <owner> --rotate   replace it; the old one dies at once
#   ./token.sh <owner> --revoke   delete it; that phone gets 401
#   ./token.sh <owner> --adb      type the token into the app's focused
#                                 token field on the connected phone
#
# Uses the caller's AWS credentials (AWS_PROFILE).
set -euo pipefail

REGION=ap-south-1
FUNCTION=poof-control-plane

usage() {
  sed -n '2,10p' "$0" >&2
  exit 2
}

OWNER=${1:-}
[[ $OWNER =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || usage
PARAM=/poof/control-plane/tokens/$OWNER

current() {
  aws ssm get-parameter --region "$REGION" --name "$PARAM" \
    --with-decryption --query Parameter.Value --output text
}

put() {
  local token
  token=$(openssl rand -base64 32)
  aws ssm put-parameter --region "$REGION" --name "$PARAM" \
    --type SecureString --value "$token" "$@" >/dev/null
  echo "$token"
}

# The Lambda reads the tokens once per cold start; any config change
# forces one. Before the first deploy there is no function to touch.
cold_start() {
  aws lambda update-function-configuration --region "$REGION" \
    --function-name "$FUNCTION" \
    --description "tokens changed $(date -u +%Y-%m-%dT%H:%M:%SZ)" >/dev/null 2>&1 ||
    echo "note: $FUNCTION not deployed; it reads the tokens when it is" >&2
}

case "${2:-}" in
  "")
    if current 2>/dev/null; then
      exit 0
    fi
    put
    cold_start
    ;;
  --rotate)
    put --overwrite
    cold_start
    echo "rotated; paste the new token on $OWNER's phone" >&2
    ;;
  --revoke)
    aws ssm delete-parameter --region "$REGION" --name "$PARAM"
    cold_start
    echo "revoked $OWNER's token" >&2
    ;;
  --adb)
    # base64 has no spaces or shell-special characters except + and /,
    # which `input text` passes through unchanged.
    adb shell input text "$(current)"
    ;;
  *)
    usage
    ;;
esac
