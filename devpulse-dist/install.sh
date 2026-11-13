#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: ./install.sh pulse|jira [--version VERSION] [--prefix DIR]
       install.sh --tool pulse|jira --version VERSION --base-url URL [--prefix DIR]
       install.sh --local --tool pulse|jira [--version VERSION] [--base-url DIR] [--prefix DIR]
       install.sh --uninstall --tool pulse|jira [--prefix DIR]

Installs one tool without Maven or administrator access (requires Java to run).
The default prefix is ~/.local. Add PREFIX/bin to PATH.
The source must contain the release tar.gz and its adjacent .tar.gz.sha256 file.
--base-url accepts HTTPS, file:// URLs, or local directory paths (quote spaces).
Defaults to local installation from target beside this script; --local is optional.
Local sources select the highest version using sort -V unless --version is given.
Version sorting requires GNU-compatible sort. The installer does not run a build.
Installing another version upgrades only the selected tool; uninstall removes
only installer-managed files for the selected tool. Reinstalling the same
version replaces its managed files after verification.
USAGE
}

die() { echo "devpulse installer: $*" >&2; exit 1; }

decode_file_path() {
    local value="$1" decoded='' escape character
    while [[ "$value" == *%* ]]; do
        decoded+="${value%%\%*}"
        value="${value#*%}"
        escape="${value:0:2}"
        [[ "$escape" =~ ^[a-fA-F0-9]{2}$ && "$escape" != 00 ]] ||
            die 'invalid percent encoding in file URL; use a plain directory path for literal %'
        printf -v character '%b' "\\x$escape"
        decoded+="$character"
        value="${value:2}"
    done
    printf '%s' "$decoded$value"
}

local_path() {
    local path="${1//\\//}"
    case "$path" in
        file://localhost/*) path="${path#file://localhost}" ;;
        file:///*) path="${path#file://}" ;;
        file://[a-zA-Z]:/*) path="${path#file://}" ;;
        file://*) die 'file URLs must use a local path or localhost; use a plain UNC path for a network share' ;;
    esac
    if [[ "$1" == file://* ]]; then
        path="$(decode_file_path "$path")" || return 1
    fi
    if [[ "$path" =~ ^/[a-zA-Z]:/ ]]; then
        path="${path:1}"
    fi
    case "$path" in
        '~') path="$HOME" ;;
        '~/'*) path="$HOME/${path:2}" ;;
    esac
    if command -v cygpath >/dev/null 2>&1; then
        if [[ "$path" =~ ^/mnt/([a-zA-Z])(/.*)?$ ]]; then
            path="${BASH_REMATCH[1]}:${BASH_REMATCH[2]:-/}"
        fi
        path="$(cygpath -u -- "$path")" || die "cannot convert local path: $1"
    elif [[ -n "${WSL_DISTRO_NAME:-}" || -n "${WSL_INTEROP:-}" ]]; then
        if [[ "$path" =~ ^([a-zA-Z]):(/.*)$ ]]; then
            local drive="${BASH_REMATCH[1],,}"
            path="/mnt/$drive${BASH_REMATCH[2]}"
        elif [[ "$path" =~ ^/([a-zA-Z])(/.*)$ ]]; then
            path="/mnt/${BASH_REMATCH[1],,}${BASH_REMATCH[2]}"
        fi
    fi
    printf '%s' "$path"
}

tool=''
version=''
prefix="${HOME:?}/.local"
base_url=''
uninstall=false
local_install=false
source_dir=''
while (($#)); do
    case "$1" in
        --tool|--version|--prefix|--base-url)
            option="$1"
            (($# >= 2)) || die "$option requires a value"
            case "$option" in
                --tool)
                    [[ -z "$tool" ]] || die 'select exactly one tool'
                    tool="$2"
                    ;;
                --version) version="$2" ;;
                --prefix) prefix="$2" ;;
                --base-url) base_url="$2" ;;
            esac
            shift 2
            ;;
        --uninstall) uninstall=true; shift ;;
        --local) local_install=true; shift ;;
        pulse|jira)
            [[ -z "$tool" ]] || die 'select exactly one tool'
            tool="$1"
            shift
            ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

[[ "$tool" == pulse || "$tool" == jira ]] || die 'use install.sh pulse or install.sh jira'
if [[ "$uninstall" == false ]]; then
    if [[ -z "$base_url" ]]; then
        base_url="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/target"
    fi
    case "$base_url" in
        https://*)
            [[ "$local_install" == false ]] || die '--local requires a local directory, not HTTPS'
            base_url="${base_url%/}"
            ;;
        *)
            [[ "$base_url" != *://* || "$base_url" == file://* ]] ||
                die '--base-url must be HTTPS, a file URL, or a local directory'
            source_dir="$(local_path "$base_url")" || exit 1
            [[ -d "$source_dir" ]] ||
                die "local release directory not found: $source_dir (from $base_url); build with bash build.sh dist or check the path"
            source_dir="$(cd -- "$source_dir" && pwd -P)"
            ;;
    esac
    if [[ -n "$source_dir" && -z "$version" ]]; then
        archives=()
        for candidate in "$source_dir"/devpulse-"$tool"-*.tar.gz; do
            [[ -f "$candidate" ]] && archives+=("$candidate")
        done
        ((${#archives[@]} > 0)) || die "no $tool release archive in $source_dir; build with bash build.sh dist"
        latest_archive="$(printf '%s\n' "${archives[@]}" | LC_ALL=C sort -V | tail -n 1)" ||
            die 'cannot sort local versions; install GNU-compatible sort or specify --version'
        version="${latest_archive##*/}"
        version="${version#devpulse-"$tool"-}"
        version="${version%.tar.gz}"
    fi
    [[ "$version" =~ ^[a-zA-Z0-9][a-zA-Z0-9._+-]*$ && "$version" != *..* ]] ||
        die 'provide a safe, explicit --version'
    if [[ -n "$source_dir" ]]; then
        for filename in "devpulse-$tool-$version.tar.gz" "devpulse-$tool-$version.tar.gz.sha256"; do
            [[ -f "$source_dir/$filename" && -r "$source_dir/$filename" ]] ||
                die "local release file not found or unreadable: $source_dir/$filename"
        done
    fi
else
    [[ -z "$version" && -z "$base_url" && "$local_install" == false ]] ||
        die '--uninstall takes only --tool and --prefix'
fi

[[ -n "$prefix" && "$prefix" != / ]] || die 'invalid --prefix'
if [[ "$uninstall" == true && ! -d "$prefix" ]]; then
    echo "$tool is not installed"
    exit 0
fi
mkdir -p -- "$prefix"
prefix="$(cd -- "$prefix" && pwd -P)"
[[ "$prefix" != / ]] || die 'invalid --prefix'
tool_home="$prefix/lib/devpulse/$tool"
wrapper="$prefix/bin/$tool"
marker='devpulse-dist-v1'

managed_wrapper() {
    [[ -f "$wrapper" && ! -L "$wrapper" ]] &&
        grep -Fqx "# devpulse-managed-wrapper: $tool" "$wrapper"
}

if [[ -e "$tool_home" || -L "$tool_home" ]]; then
    [[ -d "$tool_home" && ! -L "$tool_home" &&
       -f "$tool_home/.devpulse-managed" && ! -L "$tool_home/.devpulse-managed" &&
       "$(cat "$tool_home/.devpulse-managed")" == "$marker" ]] ||
        die "refusing to alter an unmanaged installation: $tool_home"
elif [[ "$uninstall" == true ]]; then
    echo "$tool is not installed"
    exit 0
fi

if [[ -e "$wrapper" || -L "$wrapper" ]]; then
    managed_wrapper || die "refusing to replace an unmanaged file: $wrapper"
fi

if [[ "$uninstall" == true ]]; then
    if [[ -d "$tool_home" ]]; then
        [[ ! -e "$tool_home/.install.lock" ]] || die 'installation in progress'
        for directory in "$tool_home"/*; do
            [[ -d "$directory" && ! -L "$directory" &&
               -f "$directory/.devpulse-managed" &&
               "$(cat "$directory/.devpulse-managed")" == "$marker" ]] || continue
            rm -f -- "$directory/devpulse-$tool.jar" "$directory/.devpulse-managed"
            rmdir -- "$directory" 2>/dev/null || true
        done
        rm -f -- "$tool_home/current" "$tool_home/.devpulse-managed"
        rmdir -- "$tool_home" 2>/dev/null || true
        rmdir -- "$prefix/lib/devpulse" 2>/dev/null || true
    fi
    if managed_wrapper; then rm -f -- "$wrapper"; fi
    echo "Uninstalled $tool from $prefix"
    exit 0
fi

if [[ -z "$source_dir" ]]; then
    command -v curl >/dev/null || die 'curl is required for HTTPS downloads'
fi
command -v sha256sum >/dev/null || die 'sha256sum is required'
command -v tar >/dev/null || die 'tar is required'
version_home="$tool_home/$version"
if [[ -e "$version_home" || -L "$version_home" ]]; then
    [[ -d "$version_home" && ! -L "$version_home" &&
       -f "$version_home/.devpulse-managed" && ! -L "$version_home/.devpulse-managed" &&
       "$(cat "$version_home/.devpulse-managed")" == "$marker" ]] ||
        die "refusing to replace an unmanaged version: $version_home"
    [[ ! -L "$version_home/devpulse-$tool.jar" ]] ||
        die 'refusing to replace a symlinked JAR'
fi
[[ ! -L "$tool_home/current" ]] || die 'refusing to replace a symlinked current file'
mkdir -p -- "$prefix/bin" "$tool_home"
if [[ ! -e "$tool_home/.devpulse-managed" ]]; then
    printf '%s\n' "$marker" > "$tool_home/.devpulse-managed"
fi
lock="$tool_home/.install.lock"
mkdir -- "$lock" || die 'another installation is in progress'
stage="$tool_home/.install-$version-$$"
new_wrapper="$prefix/bin/.$tool.new.$$"
new_current="$tool_home/.current.new.$$"
cleanup() {
    [[ ! -e "$stage" ]] || rm -rf -- "$stage"
    rm -f -- "$new_wrapper" "$new_current"
    rmdir -- "$lock" 2>/dev/null || true
}
trap cleanup EXIT
mkdir -- "$stage"
archive="devpulse-$tool-$version.tar.gz"
root="devpulse-$tool-$version"
if [[ -n "$source_dir" ]]; then
    cp -- "$source_dir/$archive" "$source_dir/$archive.sha256" "$stage/"
else
    curl --fail --location --silent --show-error --output "$stage/$archive" \
        "$base_url/$archive"
    curl --fail --location --silent --show-error --output "$stage/$archive.sha256" \
        "$base_url/$archive.sha256"
fi

verify_checksum() {
    local checksum_file="$1" expected_file="$2" hash filename
    read -r hash filename < "$stage/$checksum_file" ||
        die "invalid checksum file: $checksum_file"
    filename="${filename%$'\r'}"
    [[ "$hash" =~ ^[a-fA-F0-9]{64}$ &&
       ( "$filename" == "$expected_file" || "$filename" == "*$expected_file" ) ]] ||
        die "invalid checksum entry: $checksum_file"
    (cd -- "$stage" &&
        printf '%s  %s\n' "$hash" "$expected_file" | sha256sum --check --status) ||
        die "checksum mismatch: $expected_file"
}

verify_checksum "$archive.sha256" "$archive"
tar -xOzf "$stage/$archive" "$root/devpulse-$tool.jar" > "$stage/devpulse-$tool.jar"
tar -xOzf "$stage/$archive" "$root/devpulse-$tool.jar.sha256" > "$stage/devpulse-$tool.jar.sha256"
# Windows-built archives may contain CRLF launchers and version files.
tar -xOzf "$stage/$archive" "$root/bin/$tool" |
    sed $'s/\r$//' > "$stage/$tool"
tar -xOzf "$stage/$archive" "$root/version.txt" > "$stage/version.txt"
archive_version="$(cat "$stage/version.txt")"
[[ "${archive_version%$'\r'}" == "$version" ]] || die 'archive version does not match request'
verify_checksum "devpulse-$tool.jar.sha256" "devpulse-$tool.jar"
grep -Fqx "# devpulse-managed-wrapper: $tool" "$stage/$tool" ||
    die 'archive wrapper is not installer-managed'
chmod 755 "$stage/$tool"
mkdir -p -- "$version_home"
mv -f -- "$stage/devpulse-$tool.jar" "$version_home/"
printf '%s\n' "$marker" > "$version_home/.devpulse-managed"
cp -- "$stage/$tool" "$new_wrapper"
mv -f -- "$new_wrapper" "$wrapper"
printf '%s\n' "$version" > "$new_current"
mv -f -- "$new_current" "$tool_home/current"
echo "Installed $tool $version to $prefix (run $wrapper)"
