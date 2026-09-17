#!/usr/bin/env bash
# Build alm-custom-auth-boilerplate and deploy it to local AEM author and publish instances.
# Usage: ./deploy.sh [author-url] [publish-url] [user:password]
set -euo pipefail

AUTHOR_URL="${1:-http://localhost:4502}"
PUBLISH_URL="${2:-http://localhost:4503}"
AEM_CREDS="${3:-admin:admin}"
SYMBOLIC_NAME="com.adobe.alm.custom-auth-boilerplate"

cd "$(dirname "$0")"

mvn clean package -DskipTests

JAR=$(ls target/alm-custom-auth-boilerplate-*.jar | grep -v sources | head -n1)

deploy_to() {
  local aem_url="$1"

  curl -sf -u "$AEM_CREDS" \
    -F "action=install" \
    -F "bundlestartlevel=20" \
    -F "bundlefile=@${JAR}" \
    "${aem_url}/system/console/bundles" -o /dev/null

  curl -sf -u "$AEM_CREDS" \
    -F "action=start" \
    "${aem_url}/system/console/bundles/${SYMBOLIC_NAME}" -o /dev/null

  echo "Deployed and started ${SYMBOLIC_NAME} on ${aem_url}"
}

deploy_to "$AUTHOR_URL"
deploy_to "$PUBLISH_URL"
