#!/usr/bin/env bash

set -euo pipefail

REPOSITORY="${SPEED_CAMERA_GITHUB_REPOSITORY:-kill-samurai/speed-camera-offline-maps}"
WORKFLOW="build-apk.yml"
BRANCH="${1:-main}"

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI is required. Install it from https://cli.github.com/ and run: gh auth login" >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI is not authenticated. Run: gh auth login" >&2
    exit 1
fi

gh workflow run "$WORKFLOW" --repo "$REPOSITORY" --ref "$BRANCH"

echo "APK build requested for branch: $BRANCH"
echo "Follow its progress at: https://github.com/$REPOSITORY/actions/workflows/$WORKFLOW"
echo "When it finishes, download the APK from: https://github.com/$REPOSITORY/releases/tag/latest-apk"
