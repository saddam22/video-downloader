# Runs all automated tests. FFmpeg on PATH enables the media tests (otherwise they are skipped).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
mvn -B test
npm --prefix browser-extensions ci
npm --prefix browser-extensions test
