#!/bin/sh
set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    exit 0
fi

# SRCROOT is client/ios; quote it so checkouts containing spaces also work.
cd "$SRCROOT/.."
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
./gradlew :shared:embedAndSignAppleFrameworkForXcode
