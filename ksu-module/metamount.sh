#!/system/bin/sh

MODDIR=$(CDPATH= cd -P "${0%/*}" && pwd -P) || exit 1
DEV=/dev
RAM=/dev/aicecho_pmm
TARGET=/vendor/lib64
MOUNTINFO=/proc/self/mountinfo
LOCK=$RAM.lock
STATE_DIR=/data/adb/aicecho_pmm
OP_DIR=$STATE_DIR/operation.lock
OP_HELD=0
CONTEXT=u:object_r:vendor_file:s0
SRC=$MODDIR/system/vendor/lib64/libaicecho_pmm.so
BUILDING=0
LOCK_OWNER=

fail() { echo "! AICEcho metamodule: $*" >&2; exit 1; }

SELF_NS=$(readlink /proc/self/ns/mnt) || fail "cannot read mount namespace"
INIT_NS=$(readlink /proc/1/ns/mnt) || fail "cannot read PID1 mount namespace"
[ -n "$SELF_NS" ] && [ "$SELF_NS" = "$INIT_NS" ] || fail "mount namespace differs from PID1"
REAL_TARGET=$(readlink -f "$TARGET") || fail "cannot resolve vendor lib64 path"
[ "$REAL_TARGET" = "$TARGET" ] || fail "vendor lib64 path is not canonical"

inspect() {
  [ ! -L "$DEV" ] && [ ! -L "$RAM" ] && [ ! -L "$LOCK" ] || return 1
  layout=$(awk -v dev="$DEV" -v ram="$RAM" -v target="$TARGET" -v lock="$LOCK" '
    function has(s, wanted, a,n,j) {
      n=split(s,a,","); for(j=1;j<=n;j++) if(a[j]==wanted) return 1; return 0
    }
    function lower(s, a,n,j,count) {
      n=split(s,a,",")
      for(j=1;j<=n;j++) {
        if(a[j] ~ /^(upperdir|workdir)=/) return 0
        if(a[j] ~ /^lowerdir=/) {
          if(a[j]!="lowerdir=" ram "/lib64:" target) return 0
          count++
        }
      }
      return count==1
    }
    {
      for(i=7;i<=NF && $i!="-";i++); if(i+3>NF) {bad=1; next}
      fs=$(i+1); source=$(i+2); opts=$(i+3)
      if($5==dev) {d++; if($4!="/" || fs!="tmpfs") bad=1}
      if($5==ram) {
        r++; rid=$1
        if($4!="/" || fs!="tmpfs" || source!="KSU" ||
           !has($6,"nosuid") || !has($6,"nodev")) bad=1
      }
      if($5==target) {
        t++; tid=$1
        if($4!="/" || fs!="overlay" || source!="KSU" || !has($6,"ro") ||
           has($6,"rw") || !has(opts,"ro") || !lower(opts)) bad=1
      }
      if($5==lock || index($5,lock "/")==1 || index($5,ram "/")==1 ||
         index($5,target "/")==1) bad=1
    }
    END {if(bad || d!=1 || r>1 || t>1) exit 1; print rid+0,tid+0}
  ' "$MOUNTINFO") || return 1
  set -- $layout
  RAM_ID=$1 OVERLAY_ID=$2
}

safe_contents() {
  [ ! -e "$RAM" ] && return 0
  [ -d "$RAM" ] && [ ! -L "$RAM" ] || return 1
  for entry in "$RAM"/* "$RAM"/.[!.]* "$RAM"/..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    [ "$RAM_ID" != 0 ] && [ "$entry" = "$RAM/lib64" ] &&
      [ -d "$entry" ] && [ ! -L "$entry" ] || return 1
  done
  [ -d "$RAM/lib64" ] || return 0
  for entry in "$RAM/lib64"/* "$RAM/lib64"/.[!.]* "$RAM/lib64"/..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    [ "$entry" = "$RAM/lib64/libaicecho_pmm.so" ] &&
      [ -f "$entry" ] && [ ! -L "$entry" ] || return 1
  done
}

proc_start() {
  start=$(awk '{sub(/^.*\) /, ""); print $20}' "/proc/$1/stat") || return 1
  case "$start" in ''|*[!0-9]*) return 1;; esac
  printf '%s\n' "$start"
}

acquire_lock() {
  my_start=$(proc_start "$$") || return 1
  if ! mkdir "$LOCK" 2>/dev/null; then
    [ -d "$LOCK" ] && [ ! -L "$LOCK" ] || return 1
    owner_path=
    for entry in "$LOCK"/* "$LOCK"/.[!.]* "$LOCK"/..?*; do
      [ -e "$entry" ] || [ -L "$entry" ] || continue
      [ -z "$owner_path" ] && [ -d "$entry" ] && [ ! -L "$entry" ] || return 1
      owner_path=$entry
    done
    [ -n "$owner_path" ] || return 1
    owner=${owner_path##*/}
    case "$owner" in *[!0-9.]*|.*|*.|*.*.*) return 1;; *.*) ;; *) return 1;; esac
    pid=${owner%.*} old_start=${owner#*.}
    [ "$pid" -gt 1 ] 2>/dev/null || return 1
    if [ -d "/proc/$pid" ]; then
      current=$(proc_start "$pid") || return 1
      [ "$current" != "$old_start" ] || return 1
    fi
    rmdir "$owner_path" && rmdir "$LOCK" && mkdir "$LOCK" || return 1
  fi
  LOCK_OWNER=$$.$my_start
  mkdir "$LOCK/$LOCK_OWNER" || { LOCK_OWNER=; rmdir "$LOCK"; return 1; }
}

same_mounts() {
  inspect && [ "$RAM_ID" = "$1" ] && [ "$OVERLAY_ID" = "$2" ]
}

cleanup_mounts() {
  inspect && safe_contents || return 1
  old_ram=$RAM_ID old_overlay=$OVERLAY_ID
  if [ "$old_overlay" != 0 ]; then
    same_mounts "$old_ram" "$old_overlay" && safe_contents || return 1
    umount -n -l "$TARGET" || return 1
    inspect && [ "$OVERLAY_ID" = 0 ] && [ "$RAM_ID" = "$old_ram" ] || return 1
  fi
  if [ "$old_ram" != 0 ]; then
    same_mounts "$old_ram" 0 && safe_contents || return 1
    umount -n -l "$RAM" || return 1
    inspect && [ "$RAM_ID" = 0 ] && [ "$OVERLAY_ID" = 0 ] || return 1
  fi
  safe_contents || return 1
  if [ -d "$RAM" ]; then rmdir "$RAM" || return 1; fi
}

finish() {
  result=$?
  trap - 0 HUP INT TERM
  if [ "$result" != 0 ] && [ "$BUILDING" = 1 ]; then
    cleanup_mounts || echo "! AICEcho metamodule: RAM rollback refused or failed" >&2
  fi
  if [ -n "$LOCK_OWNER" ] && [ ! -L "$LOCK" ] && [ ! -L "$LOCK/$LOCK_OWNER" ]; then
    rmdir "$LOCK/$LOCK_OWNER" && rmdir "$LOCK" || result=1
  fi
  if [ "$OP_HELD" = 1 ]; then rmdir "$OP_DIR" || result=1; fi
  exit "$result"
}

case "${1:-}" in ''|cleanup) ;; *) fail "usage: metamount.sh [cleanup]";; esac
inspect && safe_contents || fail "unsafe mount layout or RAM contents"
[ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || fail "shared state directory is missing or unsafe"
umask 077
mkdir "$OP_DIR" 2>/dev/null || fail "operation lock is busy or unsafe"
OP_HELD=1
trap finish 0
trap 'exit 1' HUP INT TERM
acquire_lock || fail "mount lock is busy or unsafe"
cleanup_mounts || fail "owned mount cleanup refused or failed"

[ "${1:-}" = cleanup ] && exit 0
for flag in disable remove skip_mount; do
  [ -f "$MODDIR/$flag" ] && exit 0
done
[ -f "$SRC" ] || fail "patch library is missing"
[ -d "$TARGET" ] && [ ! -L "$TARGET" ] || fail "vendor lib64 directory is missing or unsafe"

mkdir "$RAM" || fail "cannot create RAM directory"
BUILDING=1
same_mounts 0 0 && safe_contents || fail "mount layout changed before tmpfs"
mount -n -t tmpfs -o size=4m,nosuid,nodev KSU "$RAM" || fail "tmpfs mount failed"
inspect && [ "$RAM_ID" != 0 ] && [ "$OVERLAY_ID" = 0 ] && safe_contents || fail "tmpfs ownership mismatch"
new_ram=$RAM_ID
mkdir "$RAM/lib64" || fail "cannot create RAM lib64"
cp "$SRC" "$RAM/lib64/libaicecho_pmm.so" || fail "RAM library copy failed"
chmod 0755 "$RAM" "$RAM/lib64" && chmod 0644 "$RAM/lib64/libaicecho_pmm.so" || fail "RAM permissions failed"
for path in "$RAM" "$RAM/lib64" "$RAM/lib64/libaicecho_pmm.so"; do
  chcon "$CONTEXT" "$path" || fail "RAM SELinux labeling failed"
  label=$(ls -dZ "$path") || fail "cannot verify RAM SELinux label"
  set -- $label
  case " $* " in *" $CONTEXT "*) ;; *) fail "RAM SELinux label mismatch";; esac
done
same_mounts "$new_ram" 0 && safe_contents || fail "mount layout changed before overlay"
mount -n -t overlay -o "ro,lowerdir=$RAM/lib64:$TARGET" KSU "$TARGET" || fail "OverlayFS mount failed"
inspect && [ "$RAM_ID" = "$new_ram" ] && [ "$OVERLAY_ID" != 0 ] || fail "overlay ownership mismatch"
BUILDING=0
