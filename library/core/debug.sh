#!/bin/bash

go mod download
CORE_PATH=$(go list -m -f '{{.Dir}}' github.com/exclavenetwork/exclave-core/v5 2>/dev/null || true)
if [ -n "$CORE_PATH" ] && [ -f "$CORE_PATH/transport/internet/reality/client.go" ]; then
    chmod -R u+w "$CORE_PATH/transport/internet/reality"
    sed -i 's/hello\.SessionId\[0\] = 25/hello.SessionId[0] = 26/g' "$CORE_PATH/transport/internet/reality/client.go"
    sed -i 's/hello\.SessionId\[1\] = 5/hello.SessionId[1] = 7/g' "$CORE_PATH/transport/internet/reality/client.go"
    sed -i 's/hello\.SessionId\[2\] = 16/hello.SessionId[2] = 28/g' "$CORE_PATH/transport/internet/reality/client.go"
fi

CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind -v -androidapi 21 -ldflags="-X v2ray.com/core.version=26.7.28 -X github.com/v2fly/v2ray-core/v5.version=26.7.28 -X github.com/v2fly/v2ray-core/v4.version=26.7.28 -X github.com/v2fly/v2ray-core/v5/core.version=26.7.28 -X github.com/exclavenetwork/exclave-core/v5.version=26.7.28 -X github.com/exclavenetwork/exclave-core/v5/core.version=26.7.28 -X github.com/xtls/xray-core/core.version=26.7.28 -X github.com/sagernet/sing-box/constant.Version=26.7.28" -tags="with_clash" "github.com/exclavenetwork/libexclavecore" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libexclavecore.aar $proj
fi
