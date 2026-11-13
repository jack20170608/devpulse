#!/usr/bin/env bash


jira --version


jira issue view SPT-1 > SPT-1.md

jira issue create --file=SPT-1.md --priority=Medium --labels="test,automation"

