#!/bin/sh
#
# Gradle Wrapper startup script for POSIX shells.
# Bookkeeper / Gradle 8.9
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Resolve APP_HOME, following symlinks so the wrapper can be invoked via a link.
app_path=$0
while [ -h "$app_path" ]; do
    app_dir=$(cd -P "$(dirname "$app_path")" >/dev/null 2>&1 && pwd)
    link=$(ls -ld "$app_path" | sed 's/.* -> //')
    case "$link" in
        /*) app_path=$link ;;
        *)  app_path=$app_dir/$link ;;
    esac
done
APP_HOME=$(cd -P "$(dirname "$app_path")" >/dev/null 2>&1 && pwd) || exit 1
APP_BASE_NAME=$(basename "$0")
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

warn() { echo "$*" >&2; }
die()  { echo; echo "$*" >&2; echo; exit 1; }

# OS specific support.
cygwin=false
msys=false
darwin=false
case "$(uname)" in
    CYGWIN*)        cygwin=true ;;
    Darwin*)        darwin=true ;;
    MINGW*|MSYS*)   msys=true  ;;
esac

# Locate the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    [ -x "$JAVACMD" ] || die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME
Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
else
    JAVACMD=java
    command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Raise the maximum file descriptor limit where supported.
if ! "$cygwin" && ! "$darwin" && ! "$msys"; then
    MAX_FD=$(ulimit -H -n 2>/dev/null) || warn "Could not query maximum file descriptor limit"
    if [ -n "$MAX_FD" ] && [ "$MAX_FD" != "unlimited" ]; then
        ulimit -n "$MAX_FD" >/dev/null 2>&1 || warn "Could not set maximum file descriptor limit to $MAX_FD"
    fi
fi

# For Cygwin / MSYS, convert paths to Windows format before running java.
if "$cygwin" || "$msys"; then
    APP_HOME=$(cygpath --path --mixed "$APP_HOME")
    CLASSPATH=$(cygpath --path --mixed "$CLASSPATH")
    JAVACMD=$(cygpath --unix "$JAVACMD")
fi

# Default JVM options. JAVA_OPTS and GRADLE_OPTS are intentionally unquoted so
# that they expand into multiple JVM arguments.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
