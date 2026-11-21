#!/usr/bin/env python3
"""Exercise the SQL constants actually used by RecipeRepo with SQLite, without Android."""
import re
import sqlite3
import unittest
from pathlib import Path

SCHEMA = (Path(__file__).resolve().parents[1] / "core/src/main/kotlin/app/toque/core/recipes/RecipeStorageSchema.kt").read_text()


def sql(name):
    return re.search(r'const val ' + re.escape(name) + r' = "([^"]+)"', SCHEMA).group(1)


class RecipeStorageTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute(sql("CREATE_RECIPES"))
        self.db.execute(sql("CREATE_FAVORITES"))
        self.db.executemany("INSERT INTO recipes VALUES (?,?,?,?,?)", [
            ("web-1", "Lidl", "{}", 1, "lidl"),
            ("json:a:1", "A", "{}", 1, "json:a"),
            ("json:b:1", "B", "{}", 1, "json:b"),
        ])
        self.db.execute("INSERT INTO favorites VALUES ('json:a:1')")
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def ids(self):
        return {r[0] for r in self.db.execute("SELECT id FROM recipes")}

    def test_lidl_refresh_does_not_remove_either_json_source(self):
        self.db.execute(sql("DELETE_SOURCE"), ("lidl",))
        self.assertEqual({"json:a:1", "json:b:1"}, self.ids())
        self.assertEqual(1, self.db.execute(sql("SOURCE_COUNT"), ("json:b",)).fetchone()[0])

    def test_withdrawal_and_source_removal_keep_favorites_and_other_sources(self):
        self.db.execute(sql("DELETE_UNFAVORITED_RECIPE"), ("json:a:1", "json:a"))
        self.assertIn("json:a:1", self.ids())
        self.db.execute(sql("DELETE_UNFAVORITED_SOURCE"), ("json:b",))
        self.assertEqual({"web-1", "json:a:1"}, self.ids())
        self.db.execute(sql("DELETE_RECIPE"), ("web-1", "json:a"))
        self.assertIn("web-1", self.ids())

    def test_failed_source_replacement_rolls_back_all_changes(self):
        before = self.ids()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute(sql("DELETE_UNFAVORITED_SOURCE"), ("json:b",))
                self.db.execute("INSERT INTO recipes VALUES (?,?,?,?,?)", ("bad", None, "{}", 2, "json:b"))
        self.assertEqual(before, self.ids())

    @staticmethod
    def open_db(db):
        """What RecipeRepo's onOpen does: add source_id only when missing, then the index."""
        columns = {row[1] for row in db.execute(sql("RECIPE_COLUMNS"))}
        if "source_id" not in columns:
            db.execute(sql("UPGRADE_1_2"))
        db.execute(sql("SOURCE_INDEX"))

    def test_upgrade_preserves_legacy_ids_json_and_favorites(self):
        db = sqlite3.connect(":memory:")
        db.executescript("CREATE TABLE recipes(id TEXT PRIMARY KEY,title TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL); CREATE TABLE favorites(id TEXT PRIMARY KEY);")
        db.execute("INSERT INTO recipes VALUES ('web-42','Old','{\"note\":\"keep\"}',123)")
        db.execute("INSERT INTO favorites VALUES ('web-42')")
        self.open_db(db)
        self.assertEqual(("web-42", "Old", '{"note":"keep"}', 123, "lidl"), db.execute("SELECT * FROM recipes").fetchone())
        self.assertEqual(("web-42",), db.execute("SELECT * FROM favorites").fetchone())
        db.close()

    def test_open_is_idempotent_and_keeps_version_1(self):
        # The version stays 1, so an official build without JSON sources still opens the file.
        self.assertEqual("1", re.search(r"const val VERSION = (\d+)", SCHEMA).group(1))
        self.open_db(self.db)
        self.open_db(self.db)
        self.assertEqual(3, len(self.ids()))
        # A build without source_id inserts without the column: the row counts as Lidl.
        self.db.execute("INSERT INTO recipes (id, title, json, updated) VALUES ('web-2', 'Old build', '{}', 1)")
        self.assertEqual(2, self.db.execute(sql("SOURCE_COUNT"), ("lidl",)).fetchone()[0])

    def test_lidl_titles_ignore_json_sources(self):
        # carryOver only looks at Lidl titles: a JSON recipe with a Lidl title is not "gone".
        titles = dict(self.db.execute(sql("SOURCE_TITLES"), ("lidl",)))
        self.assertEqual({"web-1": "Lidl"}, titles)


if __name__ == "__main__":
    unittest.main()
