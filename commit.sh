#!/bin/bash

# Commit all changes, optionally without pushing to remotes.
set -euo pipefail

COMMIT_MESSAGE="Auto commit"
LOCAL_ONLY=false
MESSAGE_SET=false

usage() {
    echo "Usage: bash commit.sh [--local|-l] [COMMIT_MESSAGE]"
    echo "中文：默认提交并推送到所有远程；--local / -l 仅本地提交，不推送。"
    echo "English: Commit and push to all remotes by default; --local / -l commits locally without pushing."
    echo "  -h, --help  显示帮助 / Show help"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -l|--local)
            LOCAL_ONLY=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
        *)
            if [[ "$MESSAGE_SET" == true ]]; then
                echo "Error: Only one commit message is allowed." >&2
                usage >&2
                exit 1
            fi
            COMMIT_MESSAGE="$1"
            MESSAGE_SET=true
            ;;
    esac
    shift
done

# Add all changes
echo "Adding all changes..."
git add .

# Commit
echo "Committing..."
git commit -m "$COMMIT_MESSAGE"

if [[ "$LOCAL_ONLY" == true ]]; then
    echo "Done! Committed locally without pushing."
    exit 0
fi

# Get list of remotes
REMOTES=$(git remote)
REMOTE_COUNT=$(echo "$REMOTES" | wc -l | tr -d '[:space:]')

echo "Found $REMOTE_COUNT remote(s):"
echo "$REMOTES"
echo "Pushing to all remotes..."
echo

# Push to all remotes
for REMOTE in $REMOTES; do
    echo ">>> Pushing to $REMOTE..."
    git push "$REMOTE"
    echo "<<< Pushed to $REMOTE done"
    echo
done

echo "Done! ✅"
