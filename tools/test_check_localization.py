"""Negative fixtures for the review gate: python -m unittest discover -s tools."""
from pathlib import Path
import tempfile
import unittest

import check_localization as checker


class ReviewGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.previous = checker.ROOT
        checker.ROOT = Path(self.temporary.name) / "Carriex"
        for locale, text in (("values", "Hello %1$s"), ("values-ru", "Привет, %1$s")):
            path = checker.ROOT / "app/src/main/res" / locale
            path.mkdir(parents=True)
            (path / "strings.xml").write_text(f'<resources><string name="greeting">{text}</string></resources>', encoding="utf-8")

    def tearDown(self):
        checker.ROOT = self.previous
        self.temporary.cleanup()

    def test_missing_and_incorrect_parameter_types_fail(self):
        self.assertEqual([], checker.check()[0])
        russian = checker.ROOT / "app/src/main/res/values-ru/strings.xml"
        russian.write_text('<resources><string name="greeting">Привет, %1$d</string></resources>', encoding="utf-8")
        self.assertTrue(any("placeholders differ" in error for error in checker.check()[0]))
        russian.write_text('<resources/>', encoding="utf-8")
        self.assertTrue(any("Missing Russian resource" in error for error in checker.check()[0]))

    def test_hardcoded_dynamic_ui_label_fails_but_routing_does_not(self):
        path = checker.ROOT / "app/src/main/java/com/example/carriex/ui/chat"
        path.mkdir(parents=True)
        (path / "Fixture.kt").write_text('when(section) { "Chats" -> show() }; button("New label: $name"); label("${lookup("wire_key")}")', encoding="utf-8")
        errors = checker.hardcoded_ui_errors()
        self.assertEqual(1, len(errors))
        self.assertIn("New label:", errors[0])

    def test_document_drift_requires_a_new_translation_review(self):
        source = checker.ROOT / "README.md"
        source.write_text("# Setup\nEnglish instructions\n", encoding="utf-8")
        (checker.ROOT / "README.ru.md").write_text("# Настройка\nРусская инструкция\n", encoding="utf-8")
        checker.stamp_docs()
        self.assertEqual([], checker.check()[0])
        source.write_text("# Setup\nChanged instructions\n", encoding="utf-8")
        self.assertTrue(any("changed since translation review" in error for error in checker.check()[0]))


if __name__ == "__main__":
    unittest.main()
