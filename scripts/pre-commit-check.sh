#!/usr/bin/env bash
set -e

echo "🔍 Running pre-commit quality checks..."
echo ""

echo "📋 Step 1: Checking code formatting..."
sbt scalafmtCheckAll scalafmtSbtCheck

echo ""
echo "🔧 Step 2: Running scalafix linting..."
sbt "scalafix --check"

echo ""
echo "🧪 Step 3: Running tests..."
sbt test

echo ""
echo "All quality checks passed!"
echo ""
echo "To auto-fix formatting issues, run: sbt scalafmtAll"
echo "To auto-fix scalafix issues, run: sbt scalafix"
