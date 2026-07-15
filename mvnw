#!/bin/sh
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$BASE_DIR/.mvn/wrapper/maven-wrapper.properties")
MAVEN_VERSION=3.9.11
CACHE_DIR="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="$CACHE_DIR/apache-maven-${MAVEN_VERSION}"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$CACHE_DIR"
  ARCHIVE="$CACHE_DIR/apache-maven-${MAVEN_VERSION}-bin.zip"
  echo "Downloading Maven ${MAVEN_VERSION}..."
  curl -fsSL "$DISTRIBUTION_URL" -o "$ARCHIVE"
  unzip -q -o "$ARCHIVE" -d "$CACHE_DIR"
fi
exec "$MAVEN_HOME/bin/mvn" "$@"
