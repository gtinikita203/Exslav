@echo off

set CGO_LDFLAGS=-Wl,-z,max-page-size=16384

gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -w -buildid= -X github.com/exclavenetwork/exclave-core/v5/core.version=26.7.28 -X github.com/xtls/xray-core/core.version=26.7.28 -X github.com/sagernet/sing-box/constant.Version=26.7.28" -tags="with_clash" "github.com/exclavenetwork/libexclavecore"
if errorlevel 1 (
    exit /b 1
)

set "proj=..\..\app\libs"

if exist "%proj%" (
    copy /Y libexclavecore.aar "%proj%"
)
