#!/bin/sh
# Install grokbot-alarm for one Connected AI and pair it.
# POSIX sh and curl. Safe to run again.
# Usage: install.sh --secret SECRET --server URL --agent NAME
set -eu

SHARE="${HOME}/.local/share/grokbot-alarm"
BINDIR="${HOME}/.local/bin"
SECRET=""
SERVER="https://ntfy.sh"
AGENT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --secret)
      SECRET=${2-}
      shift 2
      ;;
    --server)
      SERVER=${2-}
      shift 2
      ;;
    --agent)
      AGENT=${2-}
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [ -z "$SECRET" ] || [ -z "$AGENT" ]; then
  echo "usage: install.sh --secret SECRET --server URL --agent NAME" >&2
  exit 1
fi

mkdir -p "$SHARE" "$BINDIR"

copy_tree() {
  src=$1
  cp "$src/cli/grokbot_alarm.py" "$SHARE/grokbot_alarm.py"
  cp "$src/skill/SKILL.md" "$SHARE/SKILL.md"
}

download_base() {
  base=$1
  tmpcli=$(mktemp)
  tmpskill=$(mktemp)
  if curl -fsSL "$base/cli/grokbot_alarm.py" -o "$tmpcli" \
    && curl -fsSL "$base/skill/SKILL.md" -o "$tmpskill"; then
    mv "$tmpcli" "$SHARE/grokbot_alarm.py"
    mv "$tmpskill" "$SHARE/SKILL.md"
    echo "installed from $base" >&2
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

if [ "$installed" -eq 0 ] && [ -n "${GROKBOT_ALARM_RAW_BASES:-}" ]; then
  for base in $GROKBOT_ALARM_RAW_BASES; do
    if download_base "$base"; then
      installed=1
      break
    fi
  done
fi

if [ "$installed" -eq 0 ]; then
  script=$0
  case "$script" in
    /*) ;;
    *) script=$(pwd)/$script ;;
  esac
  srcdir=$(CDPATH= cd -- "$(dirname "$script")" && pwd)
  if [ -f "$srcdir/cli/grokbot_alarm.py" ] && [ -f "$srcdir/skill/SKILL.md" ]; then
    copy_tree "$srcdir"
    installed=1
  fi
fi

if [ "$installed" -eq 0 ]; then
  for base in \
    https://raw.githubusercontent.com/WanManDu/grokbot-alarm/main \
    https://raw.githubusercontent.com/WanManDu/grokbot-alarm/cursor/grokbot-alarm-mvp-3183
  do
    if download_base "$base"; then
      installed=1
      break
    fi
  done
fi

if [ "$installed" -eq 0 ]; then
  echo "could not fetch grokbot-alarm (main and the working branch both failed)" >&2
  exit 1
fi

chmod 755 "$SHARE/grokbot_alarm.py"

cat > "$BINDIR/grokbot-alarm" << 'EOF'
#!/bin/sh
exec python3 "$HOME/.local/share/grokbot-alarm/grokbot_alarm.py" "$@"
EOF
chmod 755 "$BINDIR/grokbot-alarm"

python3 "$SHARE/grokbot_alarm.py" init --secret "$SECRET" --server "$SERVER" --agent "$AGENT"
echo "grokbot-alarm ready for $AGENT. Read $SHARE/SKILL.md" >&2
