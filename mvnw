#!/bin/sh
# Maven Wrapper — downloads Maven 3.9.9 on first run if not already cached.
set -e

MAVEN_VERSION="3.9.9"
MAVEN_DIR="$HOME/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MVN_CMD="$MAVEN_DIR/apache-maven-${MAVEN_VERSION}/bin/mvn"

if [ ! -f "$MVN_CMD" ]; then
    echo "Downloading Apache Maven ${MAVEN_VERSION}..."
    mkdir -p "$MAVEN_DIR"
    ZIP="$MAVEN_DIR/apache-maven-${MAVEN_VERSION}-bin.zip"
    URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ZIP" "$URL"
    else
        echo "ERROR: neither curl nor wget found. Install one and retry."
        exit 1
    fi
    unzip -q "$ZIP" -d "$MAVEN_DIR"
    rm "$ZIP"
    echo "Maven ${MAVEN_VERSION} ready."
fi

exec "$MVN_CMD" "$@"
