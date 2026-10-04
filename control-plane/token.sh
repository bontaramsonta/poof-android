#!/usr/bin/env bash
# Creates, rotates or hands over the control plane bearer token.
#
#   ./token.sh            create the token if missing, print it
#   ./token.sh --rotate   replace the token; the old one dies at once
#   ./token.sh --adb      type the current token into the app's focused
#                         token field on the connected phone
#
# Uses the caller's AWS credentials (AWS_PROFILE).
set -euo pipefail

REGION=ap-south-1
PARAM=/poof/control-plane/token
FUNCTION=poof-control-plane

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

case "${1:-}" in
  "")
    if current 2>/dev/null; then
      exit 0
    fi
    put
    ;;
  --rotate)
    put --overwrite
    # Any config change forces a cold start, which re-reads the token.
    aws lambda update-function-configuration --region "$REGION" \
      --function-name "$FUNCTION" \
      --description "token rotated $(date -u +%Y-%m-%dT%H:%M:%SZ)" >/dev/null
    echo "rotated; paste the new token on the phone" >&2
    ;;
  --adb)
    # base64 has no spaces or shell-special characters except + and /,
    # which `input text` passes through unchanged.
    adb shell input text "$(current)"
    ;;
  *)
    sed -n '2,9p' "$0" >&2
    exit 2
    ;;
esac
