#!/usr/bin/env python3
"""用最小 ZIP 验证校验器可以拦下意外打包的文件。"""

from importlib.util import module_from_spec, spec_from_file_location
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
import warnings
from zipfile import ZipFile, ZipInfo

SPEC = spec_from_file_location("release_archive", Path(__file__).with_name("check-release-archive.py"))
checker = module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
VERSION = "0.0.1"
MAIN_JAR = f"leximeet-idea/lib/leximeet-idea-{VERSION}.jar"


def make_zip(entries):
    buffer = BytesIO()
    with ZipFile(buffer, mode="w") as archive:
        for name, content in entries:
            archive.writestr(name, content)
    return buffer.getvalue()


class CheckReleaseArchiveTest(unittest.TestCase):
    def check(self, extra_outer=(), extra_inner=(), logo='<path d="M4 4h32v32H4Z"/>'):
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40">{logo}</svg>'
        entries = {name: b"fixture" for name in checker.PLUGIN_FILES}
        entries.update({name: svg for name in checker.ICON_FILES})
        entries["META-INF/plugin.xml"] = (
            f"<idea-plugin><id>{checker.PLUGIN_ID}</id><name>LexiMeet</name>"
            f"<version>{VERSION}</version></idea-plugin>"
        )
        entries[f"{checker.CLASS_ROOT}/connection/DesktopLinkService.class"] = b"fixture"
        jar = make_zip([*entries.items(), *extra_inner])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "candidate.zip"
            path.write_bytes(make_zip([(MAIN_JAR, jar), *extra_outer]))
            return checker.check_zip(path, VERSION)

    def test_minimal_release_is_valid(self):
        self.assertRegex(self.check(), r"^[a-f\d]{64}  candidate\.zip\n$")

    def test_rejects_local_credential_file_at_either_level(self):
        with self.assertRaisesRegex(ValueError, "插件 ZIP"):
            self.check(extra_outer=[("leximeet-idea/private.pem", b"fixture")])
        with self.assertRaisesRegex(ValueError, "插件 JAR"):
            self.check(extra_inner=[("META-INF/private.pem", b"fixture")])

    def test_rejects_raster_or_external_reference_in_svg(self):
        for logo in ('<image href="data:image/png;base64,AA=="/>', '<use href="other.svg#icon"/>'):
            with self.subTest(logo=logo), self.assertRaisesRegex(ValueError, "图标不得包含位图或外部引用"):
                self.check(logo=logo)

    def test_rejects_ui_test_driver(self):
        with self.assertRaisesRegex(ValueError, "插件 JAR"):
            self.check(extra_inner=[(f"{checker.CLASS_ROOT}/uitest/ReadingUiActivity.class", b"fixture")])

    def test_rejects_duplicate_and_link(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with self.assertRaisesRegex(ValueError, "重复项"):
                self.check(extra_inner=[("META-INF/plugin.xml", b"fixture")])
        link = ZipInfo(f"{checker.CLASS_ROOT}/Link.class")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        with self.assertRaisesRegex(ValueError, "非普通文件"):
            self.check(extra_inner=[(link, b"fixture")])


if __name__ == "__main__":
    unittest.main()
