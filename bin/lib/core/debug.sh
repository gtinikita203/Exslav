#!/usr/bin/env bash

source "bin/init/env.sh"

HASH_FILES="library/core/go.mod library/core/go.sum library/core/main.go library/core/build.sh library/core/debug.sh"
CURRENT_HASH=""

if command -v sha256sum &> /dev/null; then
    CURRENT_HASH=$(sha256sum $HASH_FILES | sha256sum | awk '{print $1}')
elif command -v md5sum &> /dev/null; then
    CURRENT_HASH=$(md5sum $HASH_FILES | md5sum | awk '{print $1}')
fi

HASH_FILE="app/libs/.libexclavecore.debug.hash"
AAR_FILE="app/libs/libexclavecore.aar"

if [ -f "$AAR_FILE" ] && [ -f "$HASH_FILE" ] && [ "$CURRENT_HASH" != "" ] && [ "$(cat "$HASH_FILE")" == "$CURRENT_HASH" ]; then
    echo ">> Go code has not changed. Skipping libexclavecore debug build."
    exit 0
fi

rm -rf library/core/build
cd library/core
./debug.sh || exit 1

mkdir -p "$PROJECT/app/libs"
cp -f libexclavecore.aar "$PROJECT/app/libs"

if [ "$CURRENT_HASH" != "" ]; then
    echo "$CURRENT_HASH" > "$PROJECT/$HASH_FILE"
fi
