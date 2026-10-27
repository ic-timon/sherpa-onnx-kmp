#!/usr/bin/env bash
set -euo pipefail

if grep -R "mirrors.cloud.tencent.com" kotlin-js-store/yarn.lock; then
  echo "yarn.lock contains a non-portable mirror URL" >&2
  exit 1
fi

if ! grep -q "https://registry.npmjs.org/" kotlin-js-store/yarn.lock; then
  echo "yarn.lock does not use the official npm registry" >&2
  exit 1
fi
