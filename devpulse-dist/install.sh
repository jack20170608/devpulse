#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: install.sh --tool pulse|jira --version VERSION --base-url URL [--prefix DIR]
       install.sh --uninstall --tool pulse|jira [--prefix DIR]

Installs one tool without Maven or administrator access (requires Java to run).
The default prefix is ~/.local. Add PREFIX/bin to PATH.
The base URL must contain the release tar.gz and its adjacent .tar.gz.sha256 file.
Only HTTPS and file:// URLs are supported.
Installing another version upgrades only the selected tool; uninstall removes
only installer-managed files for the selected tool.
USAGE
}

die() { echo "devpulse installer: $*" >&2; exit 1; }

tool=''
version=''
prefix="${HOME:?}/.local"
base_url=''
uninstall=false
while (($#)); do
    case "$1" in
        --tool|--version|--prefix|--base-url)
            option="$1"
            (($# >= 2)) || die "$option requires a value"
            case "$option" in
                --tool) tool="$2" ;;
                --version) version="$2" ;;
                --prefix) prefix="$2" ;;
                --base-url) base_url="$2" ;;
            esac
            shift 2
            ;;
        --uninstall) uninstall=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

[[ "$tool" == pulse || "$tool" == jira ]] || die 'use --tool pulse or --tool jira'
if [[ "$uninstall" == false ]]; then
    [[ "$version" =~ ^[a-zA-Z0-9][a-zA-Z0-9._+-]*$ && "$version" != *..* ]] ||
        die 'provide a safe, explicit --version'
    [[ -n "$base_url" ]] || die 'provide an explicit --base-url'
    [[ "$base_url" == https://* || "$base_url" == file://* ]] ||
        die '--base-url must be an HTTPS or file URL'
    base_url="${base_url%/}"
else
    [[ -z "$version" && -z "$base_url" ]] ||
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

command -v curl >/dev/null || die 'curl is required'
command -v sha256sum >/dev/null || die 'sha256sum is required'
command -v tar >/dev/null || die 'tar is required'
[[ ! -e "$tool_home/$version" && ! -L "$tool_home/$version" ]] ||
    die "$tool version $version is already installed"
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
curl --fail --location --silent --show-error --output "$stage/$archive" \
    "$base_url/$archive"
curl --fail --location --silent --show-error --output "$stage/$archive.sha256" \
    "$base_url/$archive.sha256"

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
tar -xOzf "$stage/$archive" "$root/bin/$tool" > "$stage/$tool"
tar -xOzf "$stage/$archive" "$root/version.txt" > "$stage/version.txt"
[[ "$(cat "$stage/version.txt")" == "$version" ]] || die 'archive version does not match request'
verify_checksum "devpulse-$tool.jar.sha256" "devpulse-$tool.jar"
grep -Fqx "# devpulse-managed-wrapper: $tool" "$stage/$tool" ||
    die 'archive wrapper is not installer-managed'
chmod 755 "$stage/$tool"
mkdir -- "$tool_home/$version"
mv -- "$stage/devpulse-$tool.jar" "$tool_home/$version/"
printf '%s\n' "$marker" > "$tool_home/$version/.devpulse-managed"
cp -- "$stage/$tool" "$new_wrapper"
mv -f -- "$new_wrapper" "$wrapper"
printf '%s\n' "$version" > "$new_current"
mv -f -- "$new_current" "$tool_home/current"
echo "Installed $tool $version to $prefix (run $wrapper)"
