#!/bin/sh
# Install grokbot-alarm for one AI and print a pairing link.
# POSIX sh. A remote download is accepted only from a full git commit and
# only when the CLI and skill match the checksums below. Running this file
# from a checkout uses the files beside it and does not touch the network.
# Usage: install.sh [--secret SECRET] [--server URL] [--agent NAME]
set -eu

SHARE="${HOME}/.local/share/grokbot-alarm"
BINDIR="${HOME}/.local/bin"
SECRET=""
SERVER="https://ntfy.sh"
AGENT=""
# 40-hex commit to download when this script is not next to the sources.
# Empty refuses a network install. Override with GROKBOT_ALARM_REV.
# Set by the commit that records the pinned tree. Never a branch name.
PINNED_REV=""
CLI_SHA256="d4bf7e56cecfb3f14416f2165833e3f19643039fdf0a883c36b6d3a70c0bbc9e"
SKILL_SHA256="3b6f16262c06e784727871271d9c80b5669972671eca0e895c7dd719b1da099e"

while [ $# -gt 0 ]; do
  case "$1" in
    --secret)
      SECRET="${2:-}"
      shift 2
      ;;
    --server)
      SERVER="${2:-}"
      shift 2
      ;;
    --agent)
      AGENT="${2:-}"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

mkdir -p "$SHARE" "$BINDIR"
chmod 700 "$SHARE"

copy_tree() {
  src=$1
  cp "$src/cli/grokbot_alarm.py" "$SHARE/grokbot_alarm.py"
  cp "$src/skill/SKILL.md" "$SHARE/SKILL.md"
}

sha256_of() {
  file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return 0
  fi
  echo "sha256sum is required to verify the download" >&2
  return 1
}

verify_file() {
  file=$1
  want=$2
  got=$(sha256_of "$file") || return 1
  if [ "$got" != "$want" ]; then
    echo "checksum mismatch for $file" >&2
    rm -f "$file"
    return 1
  fi
}

is_commit() {
  rev=$1
  case "$rev" in
    *[!0-9a-f]*)
      return 1
      ;;
  esac
  test "${#rev}" -eq 40
}

download_pinned() {
  rev=$1
  base="https://raw.githubusercontent.com/WanManDu/grokbot-alarm/${rev}"
  tmpcli=$(mktemp)
  tmpskill=$(mktemp)
  if curl -fsSL "$base/cli/grokbot_alarm.py" -o "$tmpcli" \
    && curl -fsSL "$base/skill/SKILL.md" -o "$tmpskill" \
    && verify_file "$tmpcli" "$CLI_SHA256" \
    && verify_file "$tmpskill" "$SKILL_SHA256"; then
    mv "$tmpcli" "$SHARE/grokbot_alarm.py"
    mv "$tmpskill" "$SHARE/SKILL.md"
    echo "installed from ${rev}" >&2
    return 0
  fi
  rm -f "$tmpcli" "$tmpskill"
  return 1
}

installed=0

if [ -n "${GROKBOT_ALARM_SRC:-}" ]; then
  copy_tree "$GROKBOT_ALARM_SRC"
  installed=1
fi

if [ "$installed" -eq 0 ]; then
  script=$0
  case "$script" in
    /*) ;;
    *) script=$(pwd)/$script ;;
  esac
  srcdir=$(CDPATH='' cd -- "$(dirname -- "$script")" && pwd)
  if [ -f "$srcdir/cli/grokbot_alarm.py" ] && [ -f "$srcdir/skill/SKILL.md" ]; then
    copy_tree "$srcdir"
    installed=1
  fi
fi

if [ "$installed" -eq 0 ]; then
  rev="${GROKBOT_ALARM_REV:-$PINNED_REV}"
  if is_commit "$rev"; then
    if download_pinned "$rev"; then
      installed=1
    fi
  else
    echo "refusing to download grokbot-alarm from an unpinned branch" >&2
    echo "check out the commit in BOT.md and run sha256sum -c checksums.sha256" >&2
    exit 1
  fi
fi

if [ "$installed" -eq 0 ]; then
  echo "could not install a checksum-matched grokbot-alarm" >&2
  exit 1
fi

chmod 755 "$SHARE/grokbot_alarm.py"

cat > "$BINDIR/grokbot-alarm" << 'EOF'
#!/bin/sh
exec python3 "$HOME/.local/share/grokbot-alarm/grokbot_alarm.py" "$@"
EOF
chmod 755 "$BINDIR/grokbot-alarm"

if [ -n "$AGENT" ] && [ -n "$SECRET" ]; then
  python3 "$SHARE/grokbot_alarm.py" init --secret "$SECRET" --server "$SERVER" --agent "$AGENT"
elif [ -n "$AGENT" ]; then
  python3 "$SHARE/grokbot_alarm.py" init --server "$SERVER" --agent "$AGENT"
elif [ -n "$SECRET" ]; then
  python3 "$SHARE/grokbot_alarm.py" init --secret "$SECRET" --server "$SERVER"
else
  python3 "$SHARE/grokbot_alarm.py" init --server "$SERVER"
fi
echo "Relay the pair URL on stdout to the human. Read $SHARE/SKILL.md" >&2
