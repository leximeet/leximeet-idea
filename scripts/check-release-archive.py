#!/usr/bin/env python3
"""检查发布标签与被测试的 ZIP，按需写出附件校验和。"""

import argparse
import hashlib
from io import BytesIO
from pathlib import Path
import re
import stat
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ID = "com.github.weilhuang.leximeetidea"
PLUGIN_NAME = "LexiMeet"
CLASS_ROOT = PLUGIN_ID.replace(".", "/")
ICON_FILES = {"META-INF/pluginIcon.svg", "META-INF/pluginIcon_dark.svg"}
SVG_ROOT = "{http://www.w3.org/2000/svg}svg"
CLASS_ENTRY = re.compile(rf"{CLASS_ROOT}/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_$]+\.class")
PLUGIN_FILES = {
    "META-INF/MANIFEST.MF",
    "META-INF/leximeet-idea.kotlin_module",
    "META-INF/leximeet/LICENSE",
    "META-INF/leximeet/third-party/gradle-Apache-2.0.txt",
    "META-INF/leximeet/third-party/intellij-platform-plugin-template-Apache-2.0.txt",
    "META-INF/plugin.xml",
    *ICON_FILES,
    "icons/leximeet.png",
    "messages/MyBundle.properties",
}


def configured_version():
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    matches = re.findall(r"^version\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(matches) != 1 or not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", matches[0]):
        raise ValueError("gradle.properties 必须只有一个正式的三段式版本号")
    return matches[0]


def check_entries(archive, allowed, required, layer):
    """逐层限定正式产物，拒绝额外附件、测试类、重复项及链接。"""
    files = set()
    directories = set()
    for item in archive.infolist():
        names = directories if item.is_dir() else files
        expected_kind = stat.S_IFDIR if item.is_dir() else stat.S_IFREG
        if item.filename in names or stat.S_IFMT(item.external_attr >> 16) not in (0, expected_kind):
            raise ValueError(f"{layer} 包含重复项或非普通文件")
        names.add(item.filename)

    parents = {parent.as_posix() + "/" for file in files for parent in Path(file).parents if parent != Path(".")}
    if not required <= files or any(not allowed(name) for name in files) or not directories <= parents:
        # 意外入包的文件名也可能敏感，不直接输出。
        raise ValueError(f"{layer} 缺少必要条目或包含非发布文件")
    if archive.testzip() is not None:
        raise ValueError(f"{layer} 完整性校验失败")
    return files


def allowed_plugin_file(name):
    return name in PLUGIN_FILES or (CLASS_ENTRY.fullmatch(name) is not None and "/uitest/" not in name)


def check_logo(content):
    """Marketplace 只接受 40×40 的矢量图标；允许 SVG 内部引用。"""
    logo = ET.fromstring(content)
    if logo.tag != SVG_ROOT or (logo.get("width"), logo.get("height")) not in {("40", "40"), ("40px", "40px")}:
        raise ValueError("Marketplace 图标必须是 40×40 的 SVG")
    if any(
        node.tag.rsplit("}", 1)[-1] in {"image", "feImage"}
        or any(key.rsplit("}", 1)[-1] == "href" and not value.startswith("#") for key, value in node.attrib.items())
        for node in logo.iter()
    ):
        raise ValueError("Marketplace 图标不得包含位图或外部引用")


def check_zip(archive, version):
    with ZipFile(archive) as distribution:
        base = f"leximeet-idea/lib/leximeet-idea-{version}"
        expected = f"{base}.jar"
        settings_index = f"{base}-searchableOptions.jar"
        files = check_entries(distribution, {expected, settings_index}.__contains__, {expected}, "插件 ZIP")
        # IDEA 构建任务可为设置页生成索引。该 JAR 只允许携带索引元数据。
        if settings_index in files:
            entries = {"META-INF/MANIFEST.MF", f"p-{PLUGIN_ID}-searchableOptions.json"}
            with ZipFile(BytesIO(distribution.read(settings_index))) as index:
                check_entries(index, entries.__contains__, entries, "设置索引 JAR")
        with ZipFile(BytesIO(distribution.read(expected))) as plugin:
            check_entries(plugin, allowed_plugin_file, PLUGIN_FILES, "插件 JAR")
            descriptor = ET.fromstring(plugin.read("META-INF/plugin.xml"))
            if (descriptor.findtext("id"), descriptor.findtext("name"), descriptor.findtext("version")) != (
                PLUGIN_ID, PLUGIN_NAME, version
            ):
                raise ValueError("插件描述符的 ID、名称或版本与候选版本不一致")
            if any(plugin.getinfo(name).file_size == 0 for name in PLUGIN_FILES):
                raise ValueError("插件 JAR 包含空的必要资源")
            for name in ICON_FILES:
                check_logo(plugin.read(name))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    return f"{digest}  {archive.name}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="远端已存在的稳定版标签，例如 v0.0.1")
    parser.add_argument("--archive", type=Path, help="测试通过的原始或签名 ZIP")
    parser.add_argument("--version-only", action="store_true")
    parser.add_argument("--write-checksum", action="store_true")
    args = parser.parse_args()
    try:
        version = configured_version()
        if args.tag is not None and args.tag != f"v{version}":
            raise ValueError(f"标签必须等于 v{version}")
        if args.version_only:
            print(version)
            return
        archive = args.archive or ROOT / "build" / "distributions" / f"leximeet-idea-{version}.zip"
        checksum = check_zip(archive, version)
        if args.write_checksum:
            archive.with_suffix(".zip.sha256").write_text(checksum, encoding="utf-8")
        print(checksum, end="")
    except ValueError as error:
        parser.exit(1, f"发布校验失败：{error}\n")
    except (OSError, KeyError, ET.ParseError, BadZipFile, RuntimeError):
        parser.exit(1, "发布校验失败：无法读取或解析产物。\n")


if __name__ == "__main__":
    main()
