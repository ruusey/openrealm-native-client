#!/usr/bin/env bash
# Native Java OpenRealm desktop client launcher.
# Requires Java 17+. The data-service host defaults to 127.0.0.1.
#
# Quick install (Linux/macOS):
#   curl -s "https://get.sdkman.io" | bash
#   sdk install java 17.0.4.1-tem
#
# Usage:
#   ./run-openrealm.sh                           # prompt for login, connect to 127.0.0.1
#   ./run-openrealm.sh openrealm.net             # prompt for login, connect to a remote server
#   ./run-openrealm.sh openrealm.net <email> <password> <characterUuid>   # skip login
HOST="${1:-127.0.0.1}"
shift || true
java -jar ./target/openrealm-native-client.jar "$HOST" "$@"
