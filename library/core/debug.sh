#!/bin/bash

CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind -v -androidapi 21 -ldflags="-X github.com/exclavenetwork/exclave-core/v5/core.version=26.7.28 -X github.com/xtls/xray-core/core.version=26.7.28 -X github.com/sagernet/sing-box/constant.Version=26.7.28" -tags="with_clash" "github.com/exclavenetwork/libexclavecore" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libexclavecore.aar $proj
fi
