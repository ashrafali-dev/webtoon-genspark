#!/bin/sh
#
# Minimal POSIX launcher for the Gradle wrapper. The bootstrap JAR resolves
# the exact Gradle distribution declared in gradle/wrapper/gradle-wrapper.properties.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
exec "$JAVA_CMD" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
