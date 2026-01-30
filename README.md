# trish - GitHub Issues CLI (Clojure)

[![Build](https://github.com/eigenhombre/trish/actions/workflows/build.yml/badge.svg)](https://github.com/eigenhombre/trish/actions/workflows/build.yml)

A command-line tool for managing GitHub issues across multiple
repositories. Query, filter, and manage issues without leaving the
terminal.

Though it overlaps substantially with the `gh` tool, I have a set
of specific use cases for my workflow across multiple issues that this
tool facilitates.

This is a Clojure port, created with the help of Claude, of a similar,
unpublished Common Lisp implementation.

## Status

In daily use by me (@eigenhombre).

## Features

- Parallel fetching of multiple repositories
- Filtering by status, labels, assignees, and dates
- Provide lifecycle transitions (on-deck → in-progress →
  closed)
- Add/remove workflow labels
- Open issues in default browser

## Installation

### Quick Start

    ./build.sh
    make install  # Installs to $BINDIR or $HOME/bin

### Requirements

- Clojure CLI tools (1.11.1 or later)
- Java 11 or later
- GitHub Personal Access Token

### Setup

Set your GitHub Personal Access Token:

    export GH_PERS_PAT="your_github_token_here"

Add to your `~/.bashrc` or `~/.zshrc` to persist.

## Usage

    trish [OPTIONS] [ISSUE-NUMBER...]

### Viewing and Filtering

    trish                      # Show recently closed issues (63 days)
    trish -p, --in-progress    # Show issues tagged "in-progress"
    trish -o, --on-deck        # Show issues tagged "on-deck"
    trish -r, --recent         # Show recently updated issues (30 days)
    trish --my-issues          # Show issues assigned to you
    trish -b, --bugs           # Show issues tagged "bug"
    trish -v, --verbose        # Detailed output
    trish --raw                # Show raw GitHub JSON

### Workflow Management

    trish --workon ISSUE       # Start work: in-progress, assign to self
    trish --sibling ISSUE      # Create new issue sibling to supplied issue
                               # (easiest way to make a new issue in a particular
                               # repo)
    trish --take ISSUE         # Assign issue to yourself
    trish --drop ISSUE         # Unassign and remove workflow labels
    trish -c ISSUE             # Close issue and clean up labels
    trish --mkondeck ISSUE     # Add to on-deck queue
    trish -B ISSUE             # Mark issue as blocked
    trish -u ISSUE             # Unblock issue

### Opening in Browser

    trish -w 123 456           # Open issues #123 and #456 in browser
    trish -o -w                # Open all on-deck issues in browser

### Multiple Issues

Pass multiple issue numbers to open several issues:

    trish -w 123 456 789       # Open three issues in browser

## Common Workflows

### Start Working on an Issue

    trish -o                   # View on-deck issues
    trish --workon 123         # Mark #123 in-progress, assign to self

### Bug Triage

    trish -b                   # View all bugs
    trish --take 456           # Take ownership of bug #456
    trish -c 456               # Close when fixed

### Review Recent Activity

    trish -r                   # See what's been updated lately
    trish -r -v                # Verbose output with more details

### Plan Next Work

    trish -o                   # View on-deck issues
    trish --workon 789         # Start on #789
    trish --mkondeck 234       # Add #234 to queue for later

## Configuration

### Repositories

Create a `resources/repos.edn` file containing a vector of repository
names in the format `"owner/repo"`:

```clojure
["owner/repo1"
 "owner/repo2"
 "owner/repo3"]
```

An example configuration file is provided at
`resources/repos.edn.example` with example repositories.

### Display Settings

Default time ranges:
- Recently closed: 63 days
- Recently updated: 30 days
- Query lookback: 100 days

## Building from Source

    # Build uberjar
    ./build.sh

    # Or use make
    make uberjar

    # Run without building
    clojure -M:run

    # Clean build artifacts
    make clean

    # Install to $BINDIR (or $HOME/bin):
    make install

## Development

### Dependencies

- clj-http - HTTP client
- cheshire - JSON handling
- clojure.tools.cli - Command-line parsing
- clojure.java-time - Time manipulation
