#!/bin/bash

set -e

# Check if commit message is provided
if [ $# -eq 0 ]; then
	COMMIT_MESSAGE="Auto commit"
else
	COMMIT_MESSAGE="$1"
fi


# Add all changes
echo "Adding all changes..."
git add .

# Commit
echo "Committing..."
git commit -m "$COMMIT_MESSAGE"

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
