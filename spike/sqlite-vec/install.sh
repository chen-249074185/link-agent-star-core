#!/bin/sh
set -e

if [ -n "$NO_COLOR" ]; then
    BOLD=""
    RESET=""
else
    BOLD="\033[1m"
    RESET="\033[0m"
fi


usage() {
    cat <<EOF
sqlite-vec-install 0.1.9

USAGE:
    $0 [static|loadable] [--target=target] [--prefix=path] [--output=path]

OPTIONS:
    --target
            Specify a different target platform to install. Available targets: android-aarch64, android-armv7a, android-i686, android-x86_64, ios-aarch64, iossimulator-aarch64, iossimulator-x86_64, linux-aarch64, linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64

    --prefix
            Specify a different directory to save the binaries. Defaults to the current working directory.
        
    --output
            Specify a specific file to save the extension to. Defaults to NULL. Cannot be used with --prefix.
EOF
}




current_target() {
  if [ "$OS" = "Windows_NT" ]; then
    # TODO disambiguate between x86 and arm windows
    target="windows-x86_64"
    return 0
  fi
  case $(uname -sm) in
  "Darwin x86_64") target=macos-x86_64 ;;
  "Darwin arm64") target=macos-aarch64 ;;
  "Linux x86_64") target=linux-x86_64 ;;
  *) target=$(uname -sm);;
  esac
}



process_arguments() {
  while [[ $# -gt 0 ]]; do
      case "$1" in
          --help)
              usage
              exit 0
              ;;
          --target=*)
              target="${1#*=}"
              ;;
          --prefix=*)
              prefix="${1#*=}"
              ;;
          --output=*)
              output="${1#*=}"
              ;;
          static|loadable)
              type="$1"
              ;;
          *)
              echo "Unrecognized option: $1"
              usage
              exit 1
              ;;
      esac
      shift
  done
  if [ -z "$type" ]; then
    type=loadable
  fi
  if [ "$type" != "static" ] && [ "$type" != "loadable" ]; then
      echo "Invalid type '$type'. It must be either 'static' or 'loadable'."
      usage
      exit 1
  fi
  if [ -z "$prefix" ]; then
    prefix="$PWD"
  fi
  if [ -z "$target" ]; then
    current_target
  fi
}




main() {
    local type=""
    local target=""
    local prefix=""
    local url=""
    local checksum=""
    local output=""

    process_arguments "$@"

    echo "${BOLD}Type${RESET}: $type"
    echo "${BOLD}Target${RESET}: $target"
    echo "${BOLD}Prefix${RESET}: $prefix"
    echo "${BOLD}Output${RESET}: $output"

    case "$target-$type" in
    "android-aarch64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-android-aarch64.tar.gz"
      checksum="76f60d4d2d89d2e5070ef8f1868c52b140a10200dbe98b0c2ca7a4d02d483eaa"
      ;;
    "iossimulator-x86_64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-iossimulator-x86_64.tar.gz"
      checksum="eb49248e616b0cedfd59d60d79bfa579c877b14118265d699a53dd0716b8ac48"
      ;;
    "ios-aarch64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-ios-aarch64.tar.gz"
      checksum="3cb77b829cc42fe0544608790e19d87efd61076639bd8b78d68f4fefb8fb8561"
      ;;
    "android-i686-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-android-i686.tar.gz"
      checksum="d1a75768502e1ab050828e1e993833c622d81808233bba988c7f266607f63581"
      ;;
    "macos-x86_64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-macos-x86_64.tar.gz"
      checksum="53ad76e400786515e2edcaed2f01271dda846316390b761fadbd2dcf56aa4713"
      ;;
    "linux-x86_64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz"
      checksum="b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7"
      ;;
    "macos-aarch64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz"
      checksum="8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29"
      ;;
    "android-x86_64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-android-x86_64.tar.gz"
      checksum="11e0b3db8b1386644966788c29c90d4f2f17689985b924e0ee57936d48e55cf7"
      ;;
    "windows-x86_64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz"
      checksum="51581189d52066b4dfc6631f6d7a3eab7dedc2260656ab09ca97ab3fb8165983"
      ;;
    "linux-aarch64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-linux-aarch64.tar.gz"
      checksum="ea03d39541e478fab5974253c461e1cb5d77742f69e40cf96e3fad5bc309a37c"
      ;;
    "iossimulator-aarch64-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-iossimulator-aarch64.tar.gz"
      checksum="7db1a8077ac496b79bb0a386ab6bfa5bd507cb45c9431ab644c69bf17f597070"
      ;;
    "android-armv7a-loadable")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-android-armv7a.tar.gz"
      checksum="637a4d38cbff2c46e296451381c25c062920455d03ddc1df955cfa0f7b5df3f0"
      ;;
    "iossimulator-x86_64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-iossimulator-x86_64.tar.gz"
      checksum="64d9c2201f1f7e17871f8b55f3adb5f02ef84a10edf7500df3e1f4b40cb39f81"
      ;;
    "ios-aarch64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-ios-aarch64.tar.gz"
      checksum="c5580d94aeb641d4c300f86df6bec4504e4954fdda83f79d695b7e5dfa7026e8"
      ;;
    "macos-x86_64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-macos-x86_64.tar.gz"
      checksum="a951999f740d46fdc8b1caa0520a7338a82df7047c644967b317b62dc89a9e54"
      ;;
    "linux-x86_64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-linux-x86_64.tar.gz"
      checksum="8daf62e5c4e8f149f79a315930ccd658c739dc377dc877b901161fcb641e2ee6"
      ;;
    "macos-aarch64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-macos-aarch64.tar.gz"
      checksum="3d9fe464fa7ec1ed6ee48baa72ced3aff95a46fd88d2d8cffca32392897791cf"
      ;;
    "linux-aarch64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-linux-aarch64.tar.gz"
      checksum="1cef2bb022a0f8514aa6478015f212df29961d7bffe7ee78b07b411151ddd93e"
      ;;
    "iossimulator-aarch64-static")
      url="https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-static-iossimulator-aarch64.tar.gz"
      checksum="cb2d2cfacdb2939c67635f9ed27c47ae3f5f915d60d99961a8b57f4f5eb2d1c2"
      ;;
    *)
      echo "Unsupported platform $target" 1>&2
      exit 1
      ;;
    esac

    extension="\${url##*.}"

    if [ "$extension" = "zip" ]; then
      tmpfile="$prefix/tmp.zip"
    else
      tmpfile="$prefix/tmp.tar.gz"
    fi

    curl --fail --location --progress-bar --output "$tmpfile" "$url"

    if ! echo "$checksum  $tmpfile" | shasum -a 256 --check --status; then
      echo "Checksum fail!"  1>&2
      rm $tmpfile
      exit 1
    fi

    if [ "$extension" = "zip" ]; then
      unzip "$tmpfile" -d $prefix
      rm $tmpfile
    else
      if [ -z "$output" ]; then
        tar -xzf "$tmpfile" -C $prefix
      else
        tar -xOzf "$tmpfile" > $output
      fi
      rm $tmpfile
    fi

    echo "✅ $target $type binaries installed at $prefix."
}



main "$@"
