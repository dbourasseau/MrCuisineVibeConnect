package app.toque.core.recipes

/** Shared SQL contract, exercised with SQLite by tools/test_recipe_storage.py. */
object RecipeStorageSchema {
    /**
     * Stays 1: `source_id` is added when the database is opened (see [UPGRADE_1_2]), not by a
     * version bump, so an official build without JSON sources still opens the same database
     * (Android refuses to open a database whose version is higher than the app's).
     */
    const val VERSION = 1
    const val LIDL = "lidl"
    const val CREATE_RECIPES = "CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL, source_id TEXT NOT NULL DEFAULT 'lidl')"
    const val CREATE_FAVORITES = "CREATE TABLE favorites (id TEXT PRIMARY KEY)"
    /** Columns of `recipes`: no `source_id` means a database written by a build without JSON sources. */
    const val RECIPE_COLUMNS = "PRAGMA table_info(recipes)"
    const val UPGRADE_1_2 = "ALTER TABLE recipes ADD COLUMN source_id TEXT NOT NULL DEFAULT 'lidl'"
    const val SOURCE_INDEX = "CREATE INDEX IF NOT EXISTS recipes_source_id ON recipes(source_id)"
    const val SOURCE_IDS = "SELECT id FROM recipes WHERE source_id = ?"
    const val SOURCE_TITLES = "SELECT id, title FROM recipes WHERE source_id = ?"
    const val SOURCE_COUNT = "SELECT COUNT(*) FROM recipes WHERE source_id = ?"
    const val DELETE_SOURCE = "DELETE FROM recipes WHERE source_id = ?"
    const val DELETE_RECIPE = "DELETE FROM recipes WHERE id = ? AND source_id = ?"
    const val DELETE_UNFAVORITED_RECIPE = "DELETE FROM recipes WHERE id = ? AND source_id = ? AND id NOT IN (SELECT id FROM favorites)"
    const val DELETE_UNFAVORITED_SOURCE = "DELETE FROM recipes WHERE source_id = ? AND id NOT IN (SELECT id FROM favorites)"
}
