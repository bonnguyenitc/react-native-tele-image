#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Publish react-native-tele-image to npm
#
# Usage:
#   ./deploy.sh              # patch bump (0.1.0 → 0.1.1)
#   ./deploy.sh minor        # minor bump (0.1.0 → 0.2.0)
#   ./deploy.sh major        # major bump (0.1.0 → 1.0.0)
#   ./deploy.sh --dry-run    # preview without publishing
# =============================================================================

set -euo pipefail

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
RESET='\033[0m'

log()     { echo -e "${BLUE}▶${RESET} $*"; }
success() { echo -e "${GREEN}✓${RESET} $*"; }
warn()    { echo -e "${YELLOW}⚠${RESET} $*"; }
error()   { echo -e "${RED}✗${RESET} $*" >&2; exit 1; }
header()  { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════${RESET}"; echo -e "${BOLD} $*${RESET}"; echo -e "${BOLD}${BLUE}══════════════════════════════════════${RESET}\n"; }

# ── Args ──────────────────────────────────────────────────────────────────────
BUMP="${1:-patch}"
DRY_RUN=false

if [[ "$BUMP" == "--dry-run" ]]; then
  DRY_RUN=true
  BUMP="patch"
fi

if [[ "$*" == *"--dry-run"* ]]; then
  DRY_RUN=true
fi

# ── Resolve script directory ──────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

header "react-native-tele-image — Deploy"

PACKAGE_NAME=$(node -p "require('./package.json').name")
CURRENT_VERSION=$(node -p "require('./package.json').version")

log "Package : ${BOLD}${PACKAGE_NAME}${RESET}"
log "Version : ${BOLD}v${CURRENT_VERSION}${RESET}"
log "Bump    : ${BOLD}${BUMP}${RESET}"
[[ "$DRY_RUN" == true ]] && warn "DRY RUN — nothing will be published"

# ── Preflight checks ──────────────────────────────────────────────────────────
header "1/5 · Preflight"

# Git working tree must be clean
if [[ -n "$(git status --porcelain)" ]]; then
  error "Working tree is dirty. Commit or stash your changes first."
fi
success "Git working tree is clean"

# Must be on main or master
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" != "main" && "$BRANCH" != "master" ]]; then
  warn "Not on main/master (currently on '${BRANCH}'). Continue? [y/N]"
  read -r CONFIRM
  [[ "$CONFIRM" =~ ^[Yy]$ ]] || error "Aborted."
fi
success "Branch: ${BRANCH}"

# npm login check
if ! npm whoami &>/dev/null; then
  error "Not logged in to npm. Run: npm login"
fi
NPM_USER=$(npm whoami)
success "npm user: ${NPM_USER}"

# ── Install deps ──────────────────────────────────────────────────────────────
header "2/5 · Install dependencies"

log "yarn install..."
yarn install --immutable
success "Dependencies installed"

# ── Build ─────────────────────────────────────────────────────────────────────
header "3/5 · Build"

log "Cleaning previous lib/..."
yarn clean 2>/dev/null || true

log "Running bob build (react-native-builder-bob)..."
yarn prepare
success "Build complete → lib/"

# Verify lib/ was produced
if [[ ! -d "$SCRIPT_DIR/lib" ]]; then
  error "Build failed: lib/ directory not found"
fi
success "lib/ directory verified"

# ── Typecheck ─────────────────────────────────────────────────────────────────
header "4/5 · Typecheck"

log "tsc..."
yarn typecheck
success "Typecheck passed"

# ── Publish ───────────────────────────────────────────────────────────────────
header "5/5 · Publish"

if [[ "$DRY_RUN" == true ]]; then
  warn "DRY RUN — skipping version bump and npm publish"
  log "What would run:"
  echo "  yarn release --ci --increment=${BUMP}"
  echo ""
  log "npm pack preview:"
  npm pack --dry-run
else
  log "Bumping version (${BUMP}) and publishing via release-it..."
  yarn release --ci --increment="${BUMP}"
  success "🎉 Published ${PACKAGE_NAME} to npm!"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}══════════════════════════════════════${RESET}"
if [[ "$DRY_RUN" == true ]]; then
  echo -e "${YELLOW}${BOLD}  Dry run complete — nothing published  ${RESET}"
else
  NEW_VERSION=$(node -p "require('./package.json').version")
  echo -e "${GREEN}${BOLD}  Done! v${NEW_VERSION} is live on npm 🚀     ${RESET}"
  echo ""
  echo -e "  ${BLUE}https://www.npmjs.com/package/${PACKAGE_NAME}${RESET}"
fi
echo -e "${GREEN}${BOLD}══════════════════════════════════════${RESET}"
echo ""
