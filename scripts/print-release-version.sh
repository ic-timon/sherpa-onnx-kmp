#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints the release version. One source of truth, so a workflow step does not
# have to grep a build file or repeat a literal.
#
# Usage:
#   ./scripts/print-release-version.sh [explicit-version]
set -euo pipefail

source "$(dirname "$0")/lib.sh"

release_version "${1:-}"
