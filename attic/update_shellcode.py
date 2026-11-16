#!/usr/bin/env python3
"""Build shellcode and replace only the generated Java method bodies."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import textwrap


ATTIC = Path(__file__).resolve().parent
JAVA_DIR = ATTIC.parent / "core-syscall/src/main/java/dev/tmpfs/libcoresyscall/core/impl/arch"
DEFAULT_NDK_VERSION = "27.0.12077973"
# ABI: (compiler target, Java class suffix). Keep the API levels explicit.
ABIS = {
    "armeabi-v7a": ("armv7a-linux-androideabi21", "Arm32"),
    "arm64-v8a": ("aarch64-linux-android21", "Arm64"),
    "x86": ("i686-linux-android21", "X86"),
    "x86_64": ("x86_64-linux-android21", "X86_64"),
    "riscv64": ("riscv64-linux-android35", "Riscv64"),
}
METHOD_SYMBOLS = {
    "getNativeDebugBreakOffset": "NativeBridge_breakpoint",
    "getNativeClearCacheOffset": "NativeBridge_nativeClearCache",
    "getNativeSyscallOffset": "NativeBridge_nativeSyscall",
    **{f"getNativeCallPointerFunction{i}Offset": f"NativeBridge_nativeCallPointerFunction{i}"
       for i in range(5)},
    "getNativeGetJavaVmOffset": "NativeBridge_nativeGetJavaVM",
    "getFakeStat64Offset": "fake_fstat64",
    "getFakeMmap64Offset": "fake_mmap64",
    "getFakeMmapOffset": "fake_mmap",
}
HOOK_SYMBOL = "_ZZ13get_hook_infoE9sHookInfo"
SECTIONS = (".text", ".rodata", ".got.plt", ".ARM.exidx")
COMMON_FLAGS = (
    "-O3", "-fPIC", "-fvisibility=hidden", "-fno-plt", "-fno-omit-frame-pointer",
    "-Wall", "-Werror=date-time",
)
C_FLAGS = ("-std=c11",)
CXX_FLAGS = ("-std=c++23", "-fvisibility-inlines-hidden", "-fno-rtti", "-fno-exceptions")
LINK_FLAGS = (
    "-shared", "-nostdlib",
    "-Wl,-Bsymbolic,--no-allow-shlib-undefined,--no-undefined,-z,defs,-z,now,-z,relro,--gc-sections",
    "-Wl,--build-id=none", "-Wl,-T,shellcode.ld",
)


class BuildError(Exception):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def select_abis(values):
    names = [name for value in values for name in value.split(",")]
    if names == ["all"]:
        return list(ABIS)
    aliases = {"arm": "armeabi-v7a", "arm64": "arm64-v8a"}
    names = [aliases[name] if name in aliases else name for name in names]
    unknown = set(names) - ABIS.keys()
    if unknown:
        raise BuildError("Unknown ABI(s): " + ", ".join(sorted(unknown)))
    return [abi for abi in ABIS if abi in names]


def find_toolchain(path, version, host=None):
    # Accept a specific NDK, the SDK root, or the SDK's ndk directory.
    path = path.expanduser().resolve()
    candidates = (path, path / version, path / "ndk" / version)
    ndk = next((p for p in candidates if (p / "source.properties").is_file()), None)
    if ndk is None:
        raise BuildError(f"NDK {version} not found under {path}")
    properties = (ndk / "source.properties").read_text(encoding="utf-8")
    match = re.search(r"^Pkg\.Revision\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if not match or match[1] != version:
        actual = match[1] if match else "unknown"
        raise BuildError(f"Expected NDK {version}, found {actual}; use --ndk-version to select it")
    host = host or sys.platform
    tags = {"linux": "linux-x86_64", "win32": "windows-x86_64"}
    if host not in tags:
        raise BuildError(f"Unsupported build host: {host}; use Windows or Linux")
    toolchain = ndk / "toolchains/llvm/prebuilt" / tags[host]
    suffix = ".exe" if host == "win32" else ""
    tools = {name: toolchain / "bin" / (name + suffix)
             for name in ("clang", "clang++", "llvm-readobj", "llvm-objcopy")}
    for tool in tools.values():
        if not tool.is_file():
            raise BuildError(f"Missing NDK tool: {tool}")
    return toolchain, tools


def build_environment():
    env = os.environ.copy()
    # Ignore host include paths and compiler overrides; do not consume CXXFLAGS.
    for key in ("CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "OBJC_INCLUDE_PATH",
                "LIBRARY_PATH", "COMPILER_PATH", "GCC_EXEC_PREFIX", "CCC_OVERRIDE_OPTIONS",
                "CLANG_CONFIG_FILE_SYSTEM_DIR", "CLANG_CONFIG_FILE_USER_DIR"):
        env.pop(key, None)
    env.update(LC_ALL="C", TZ="UTC", SOURCE_DATE_EPOCH="0")
    return env


def run(command, env):
    result = subprocess.run([str(arg) for arg in command], cwd=ATTIC, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            encoding="utf-8", errors="replace", check=False)
    if result.returncode:
        raise BuildError(f"{Path(command[0]).name} failed ({result.returncode}):\n"
                         + result.stdout + result.stderr)
    if result.stderr:
        print(result.stderr, end="", file=sys.stderr)
    return result.stdout


def inspect_elf(elf, metadata, blob):
    """Validate the linked image and compute offsets relative to extracted bytes."""
    if elf[:4] != b"\x7fELF" or elf[4:6] not in (b"\x01\x01", b"\x02\x01"):
        raise BuildError("Expected a little-endian ELF32 or ELF64 image")
    sections = {entry["Section"]["Name"]["Name"]: entry["Section"]
                for entry in metadata["Sections"]}
    copied = [sections[name] for name in SECTIONS if name in sections and sections[name]["Size"]]
    if ".text" not in sections or not copied:
        raise BuildError("ELF has no shellcode .text section")
    base = min(section["Address"] for section in copied)
    end = max(section["Address"] + section["Size"] for section in copied)
    if base != sections[".text"]["Address"] or base % 4096:
        raise BuildError("Shellcode must start at the page-aligned .text section")
    if not 0 < len(blob) <= 4096 or len(blob) != end - base:
        raise BuildError(f"Invalid shellcode size: {len(blob)} (must fit in one 4 KiB page)")
    # Verify objcopy retained the address gaps and bytes of every selected section.
    expected = bytearray(end - base)
    for section in sorted(copied, key=lambda s: s["Address"]):
        offset, size = section["Offset"], section["Size"]
        data = elf[offset:offset + size]
        if len(data) != size or section["Type"]["Value"] == 8:
            raise BuildError("Shellcode section has no file contents")
        rva = section["Address"] - base
        expected[rva:rva + size] = data
    if blob != expected:
        raise BuildError("Extracted bytes do not match the ELF section layout")
    metadata_sections = {".dynamic", ".dynsym", ".dynstr", ".hash", ".gnu.hash",
                         ".eh_frame", ".eh_frame_hdr", ".note.gnu.build-id"}
    for name, section in sections.items():
        if not section["Size"]:
            continue
        kind = section["Type"]["Value"]
        if kind in (4, 9, 19, 0x60000001, 0x60000002):
            raise BuildError(f"Shellcode needs runtime relocations: {name}")
        if section["Flags"]["Value"] & 2 and name not in SECTIONS + tuple(metadata_sections):
            raise BuildError(f"Unexpected allocated section: {name}")
        if kind == 6:  # SHT_DYNAMIC; read the section, not the unused PT_DYNAMIC segment.
            fmt = "<iI" if elf[4] == 1 else "<qQ"
            data = elf[section["Offset"]:section["Offset"] + section["Size"]]
            if any(tag == 1 for tag, _ in struct.iter_unpack(fmt, data)):
                raise BuildError("Shellcode has a DT_NEEDED dependency")
    symbols = {}
    for entry in metadata["Symbols"]:
        symbol = entry["Symbol"]
        name = symbol["Name"]["Name"]
        if name and symbol["Section"]["Value"] == 0:
            raise BuildError(f"Undefined symbol: {name}")
        if name in METHOD_SYMBOLS.values() or name == HOOK_SYMBOL:
            if name in symbols:
                raise BuildError(f"Duplicate symbol: {name}")
            section = symbol["Section"]["Name"]
            if section not in SECTIONS or (name != HOOK_SYMBOL and section != ".text"):
                raise BuildError(f"Symbol outside shellcode: {name}")
            rva = symbol["Value"] - base
            # ARM function addresses may carry a Thumb bit; retain it in the RVA.
            address = rva & ~1 if name != HOOK_SYMBOL and elf[18:20] == b"\x28\x00" else rva
            if not 0 <= address < len(blob) or address + symbol["Size"] > len(blob):
                raise BuildError(f"Symbol out of bounds: {name}")
            symbols[name] = rva
    missing = (set(METHOD_SYMBOLS.values()) | {HOOK_SYMBOL}) - symbols.keys()
    if missing:
        raise BuildError("Missing shellcode symbols: " + ", ".join(sorted(missing)))
    hook = symbols[HOOK_SYMBOL]
    if blob[hook:hook + 24] != struct.pack("<QQQ", 0xdeafbeef, 0x114514, 0x1000):
        raise BuildError("HookInfo layout or initial values changed")
    return symbols


def replace_body(source, method, lines):
    # Replace only text between the method's '{\n' and '\n    }' delimiters.
    result_type = r"byte\[\]" if method == "getShellcodeBytes" else "int"
    pattern = re.compile(
        r"^(?P<indent>[ \t]+)public " + result_type + " " + re.escape(method)
        + r"\(\) \{(?P<newline>\r?\n)(?P<body>.*?)(?P<end>\r?\n(?P=indent)\})",
        re.MULTILINE | re.DOTALL)
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise BuildError(f"Expected exactly one delimited body for {method}, found {len(matches)}")
    match = matches[0]
    # A missing closing delimiter must not consume the next method or class.
    for line in match["body"].splitlines():
        indentation = line[:len(line) - len(line.lstrip(" \t"))]
        if line.strip() and len(indentation) <= len(match["indent"]):
            raise BuildError(f"Unexpected member boundary inside {method}")
    body = match["newline"].join(match["indent"] + "    " + line for line in lines)
    return source[:match.start("body")] + body + source[match.start("end"):]


def render_java(original, blob, symbols):
    source = original.decode("utf-8")
    lines = ["// Generated by attic/update_shellcode.py; offsets are relative to .text."]
    for name, rva in sorted(symbols.items(), key=lambda item: (item[1], item[0])):
        lines.append(f"// {rva:04x} {name}")
    chunks = textwrap.wrap(base64.b64encode(blob).decode("ascii"), 76)
    lines.append("String b64 =")
    for index, chunk in enumerate(chunks):
        suffix = ";" if index == len(chunks) - 1 else " +"
        lines.append('        "' + chunk + r'\n"' + suffix)
    lines.extend([
        "byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);",
        f"int hookInfoOffset = 0x{symbols[HOOK_SYMBOL]:04x};",
        "fillInHookInfo(bytes, hookInfoOffset);", "return bytes;",
    ])
    source = replace_body(source, "getShellcodeBytes", lines)
    for method, name in METHOD_SYMBOLS.items():
        source = replace_body(source, method, [f"return 0x{symbols[name]:04x};"])
    return source.encode("utf-8")


def build_abi(abi, toolchain, tools, output, env):
    output.mkdir(parents=True, exist_ok=True)
    elf_path = output / "shellcode.so"
    bin_path = output / "shellcode.bin"
    target, _ = ABIS[abi]
    common_args = ["--no-default-config", f"--target={target}",
                   f"--sysroot={toolchain / 'sysroot'}", "-I.", *COMMON_FLAGS,
                   f"-ffile-prefix-map={ATTIC}=.", f"-ffile-prefix-map={toolchain}=ndk"]
    c_objects = []
    for source in ("syscall_ext.c", "clear_cache.c"):
        object_path = output / Path(source).with_suffix(".o")
        run([tools["clang"], *common_args, *C_FLAGS, "-c", source, "-o", object_path], env)
        c_objects.append(object_path)
    run([tools["clang++"], *common_args, *CXX_FLAGS, *LINK_FLAGS,
         "all_in_one.cc", *c_objects, "-o", elf_path], env)
    run([tools["llvm-objcopy"], "-O", "binary",
         *(f"--only-section={name}" for name in SECTIONS), elf_path, bin_path], env)
    metadata = json.loads(run([tools["llvm-readobj"], "--elf-output-style=JSON",
                               "--sections", "--symbols", elf_path], env))[0]
    blob = bin_path.read_bytes()
    symbols = inspect_elf(elf_path.read_bytes(), metadata, blob)
    return blob, symbols


def atomic_write(path, data):
    mode = path.stat().st_mode
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as f:
        temporary = Path(f.name)
        f.write(data)
    try:
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True,
                        help="NDK installation, Android SDK root, or SDK ndk directory")
    parser.add_argument("--ndk-version", "--toolchain-version", default=DEFAULT_NDK_VERSION,
                        help=f"exact NDK package revision (default: {DEFAULT_NDK_VERSION})")
    parser.add_argument("--abis", nargs="+", default=["all"],
                        help="all (default), or space/comma separated " + ", ".join(ABIS))
    parser.add_argument("--build-dir", type=Path, default=ATTIC / "build/update-shellcode",
                        help="directory for ELF, binary, and manifest build products")
    parser.add_argument("--check", action="store_true",
                        help="build and fail if Java embeddings differ; do not edit Java")
    args = parser.parse_args(argv)
    try:
        abis = select_abis(args.abis)
        toolchain, tools = find_toolchain(args.ndk, args.ndk_version)
        output = args.build_dir.expanduser().resolve()
        originals = {abi: (JAVA_DIR / f"ShellcodeImpl_{ABIS[abi][1]}.java").read_bytes()
                     for abi in abis}
        # Validate the replacement boundaries before compiling anything.
        for original in originals.values():
            for method in ("getShellcodeBytes", *METHOD_SYMBOLS):
                replace_body(original.decode("utf-8"), method, ["placeholder"])
        updates = {}
        manifest = {"ndk_version": args.ndk_version,
                    "flags": {"common": list(COMMON_FLAGS), "c": list(C_FLAGS),
                              "cxx": list(CXX_FLAGS), "link": list(LINK_FLAGS)}, "abis": {},
                    "sources": {p.name: sha256(p.read_bytes().replace(b"\r\n", b"\n"))
                                for p in sorted(ATTIC.iterdir())
                                if p.suffix in (".c", ".cc", ".h", ".ld") or p == Path(__file__).resolve()}}
        env = build_environment()
        for abi in abis:
            print(f"Building {abi} with NDK {args.ndk_version}...", flush=True)
            blob, symbols = build_abi(abi, toolchain, tools, output / abi, env)
            updates[abi] = render_java(originals[abi], blob, symbols)
            manifest["abis"][abi] = {"target": ABIS[abi][0], "size": len(blob),
                                     "sha256": sha256(blob), "rvas": symbols}
        (output / "manifest.json").write_bytes(
            (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("ascii"))
        # All builds and validations must succeed before changing any Java file.
        paths = {abi: JAVA_DIR / f"ShellcodeImpl_{ABIS[abi][1]}.java" for abi in abis}
        for abi, path in paths.items():
            if path.read_bytes() != originals[abi]:
                raise BuildError(f"Source changed while building: {path}")
        changed = False
        for abi, path in paths.items():
            differs = updates[abi] != originals[abi]
            changed |= differs
            if differs and not args.check:
                atomic_write(path, updates[abi])
            state = ("stale" if args.check else "updated") if differs else "up to date"
            print(f"{path.name}: {state}")
        return 1 if args.check and changed else 0
    except (BuildError, OSError, ValueError, KeyError, struct.error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
