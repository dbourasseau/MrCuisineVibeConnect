package app.toque.recipes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.JsonRecipeSource
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeStorageSchema
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.adjusted
import app.toque.Prefs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Recettes Lidl copiées localement (SQLite) + index de recherche en mémoire.
 * L'appli fonctionne entièrement hors ligne ; seule la synchro passe par le réseau.
 */
class RecipeRepo(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "recettes.db", null, RecipeStorageSchema.VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(RecipeStorageSchema.CREATE_RECIPES)
            db.execSQL(RecipeStorageSchema.SOURCE_INDEX)
            db.execSQL(RecipeStorageSchema.CREATE_FAVORITES)
        }

        // Tables et colonne ajoutées sans changer le numéro de version de la base : une version
        // plus ancienne de l'appli, qui les ignore, peut toujours ouvrir la même base.
        override fun onOpen(db: SQLiteDatabase) {
            if (!db.isReadOnly) {
                db.execSQL("CREATE TABLE IF NOT EXISTS adjustments (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
                // Recettes cuisinées : nombre de fois et dernière fois (ms), pour « Récentes » et
                // « Les plus cuisinées ».
                db.execSQL("CREATE TABLE IF NOT EXISTS cooked (id TEXT PRIMARY KEY, count INTEGER NOT NULL, last INTEGER NOT NULL)")
                // Source de chaque recette (« lidl » ou une source JSON).
                if (!hasColumn(db, "source_id")) db.execSQL(RecipeStorageSchema.UPGRADE_1_2)
                db.execSQL(RecipeStorageSchema.SOURCE_INDEX)
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        // Une base laissée en version 2 par une compilation de la PR #16 telle quelle : rien à
        // défaire, la colonne source_id est la même.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        private fun hasColumn(db: SQLiteDatabase, name: String): Boolean =
            db.rawQuery(RecipeStorageSchema.RECIPE_COLUMNS, null).use { c ->
                val col = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) if (c.getString(col) == name) return true
                false
            }
    }
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-recettes").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    @Volatile var index: SearchIndex = SearchIndex(emptyList())
        private set
    @Volatile var loaded = false
        private set
    private val favorites = HashSet<String>()
    /** Une recette cuisinée [count] fois, la dernière à [lastMs]. */
    class Usage(val count: Int, val lastMs: Long)
    private val cooked = ConcurrentHashMap<String, Usage>()
    /** Adaptations personnelles (note, quantités, pesées), par identifiant de recette. */
    private val adjustments = ConcurrentHashMap<String, RecipeAdjustments>()
    @Volatile private var adjustmentsLoaded = false

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun notifyChanged() = main.post { for (l in ArrayList(listeners)) l() }

    fun load() = exec.execute { rebuild() }

    private fun rebuild() {
        val db = helper.writableDatabase
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        val list = ArrayList<Recipe>()
        val hideCatalog = Prefs(context).hideCatalog
        db.rawQuery("SELECT json, source_id FROM recipes", null).use { c ->
            while (c.moveToNext()) {
                // Option « Masquer le catalogue officiel » : les recettes de la synchro Lidl /
                // monsieur-cuisine.com restent en base mais ne sont ni listées ni cherchées.
                if (hideCatalog && c.getString(1) == RecipeStorageSchema.LIDL) continue
                val r = try {
                    RecipeCodec.decode(c.getString(0), RecipeSource.IMPORTED)
                } catch (_: Exception) {
                    null
                } ?: continue
                list += r.copy(favorite = r.id in favs)
            }
        }
        synchronized(favorites) {
            favorites.clear()
            favorites += favs
        }
        val usage = HashMap<String, Usage>()
        db.rawQuery("SELECT id, count, last FROM cooked", null).use { c ->
            while (c.moveToNext()) usage[c.getString(0)] = Usage(c.getInt(1), c.getLong(2))
        }
        cooked.clear()
        cooked.putAll(usage)
        index = SearchIndex(list)
        loaded = true
        notifyChanged()
    }

    val count: Int get() = index.entries.size
    val lidlCount: Int get() = exec.submit<Int> {
        helper.readableDatabase.rawQuery(RecipeStorageSchema.SOURCE_COUNT, arrayOf(RecipeStorageSchema.LIDL)).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }.get()

    /** Lecture d'une recette (rapide : une seule ligne). */
    fun get(id: String): Recipe? {
        helper.readableDatabase.rawQuery("SELECT json FROM recipes WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            val r = try {
                RecipeCodec.decode(c.getString(0), RecipeSource.IMPORTED)
            } catch (_: Exception) {
                null
            } ?: return null
            return r.copy(favorite = isFavorite(id))
        }
    }

    /** La recette avec les adaptations de l'utilisateur (quantités et pesées corrigées). */
    fun getAdjusted(id: String): Recipe? = get(id)?.let { it.adjusted(adjustmentsOf(it.id)) }

    private fun loadAdjustments(db: SQLiteDatabase) {
        if (adjustmentsLoaded) return
        synchronized(adjustments) {
            if (adjustmentsLoaded) return
            db.rawQuery("SELECT id, json FROM adjustments", null).use { c ->
                while (c.moveToNext()) {
                    val a = RecipeAdjustments.decode(c.getString(1))
                    if (!a.isEmpty) adjustments[c.getString(0)] = a
                }
            }
            adjustmentsLoaded = true
        }
    }

    /** Adaptations de l'utilisateur pour cette recette. Conservées d'une synchronisation à l'autre. */
    fun adjustmentsOf(id: String): RecipeAdjustments {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        return adjustments[id] ?: RecipeAdjustments()
    }

    fun setAdjustments(id: String, a: RecipeAdjustments) {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        if (a.isEmpty) adjustments.remove(id) else adjustments[id] = a
        exec.execute {
            val db = helper.writableDatabase
            if (a.isEmpty) db.delete("adjustments", "id = ?", arrayOf(id))
            else db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                put("id", id)
                put("json", a.encode())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun isFavorite(id: String): Boolean = synchronized(favorites) { id in favorites }

    fun setFavorite(id: String, favorite: Boolean) {
        synchronized(favorites) { if (favorite) favorites += id else favorites -= id }
        exec.execute {
            val db = helper.writableDatabase
            if (favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(id))
            else db.execSQL("DELETE FROM favorites WHERE id = ?", arrayOf(id))
            rebuild()
        }
    }

    /**
     * Remplace le catalogue Lidl par l'import du jour (miroir de l'appli d'origine).
     * Les favoris sont conservés ; ceux marqués dans l'appli d'origine sont ajoutés.
     */
    fun replaceAll(recipes: List<Recipe>) {
        exec.submit { replaceAllNow(recipes) }.get()
    }

    private fun replaceAllNow(recipes: List<Recipe>) {
        val db = helper.writableDatabase
        val before = titles(db, RecipeStorageSchema.LIDL)
        db.beginTransaction()
        try {
            db.execSQL(RecipeStorageSchema.DELETE_SOURCE, arrayOf(RecipeStorageSchema.LIDL))
            val now = System.currentTimeMillis()
            for (r in recipes) {
                val v = ContentValues()
                v.put("id", r.id)
                v.put("title", r.title)
                v.put("json", RecipeCodec.encode(r.copy(favorite = false)))
                v.put("updated", now)
                v.put("source_id", RecipeStorageSchema.LIDL)
                db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                if (r.favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(r.id))
            }
            val kept = recipes.map { it.id }.toHashSet()
            carryOver(db, before.filterKeys { it !in kept })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        rebuild()
    }

    /** Ajoute ou met à jour des recettes (favoris conservés). [rebuild] : recharger l'index tout de suite. */
    fun upsertAll(recipes: List<Recipe>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (r in recipes) {
                    val v = ContentValues()
                    v.put("id", r.id)
                    v.put("title", r.title)
                    v.put("json", RecipeCodec.encode(r.copy(favorite = false)))
                    v.put("updated", now)
                    v.put("source_id", RecipeStorageSchema.LIDL)
                    db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) rebuild()
        }.get()
    }

    /** Identifiants de toutes les recettes enregistrées. */
    fun ids(): Set<String> = exec.submit<Set<String>> {
        val out = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT id FROM recipes", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }.get()

    /** Supprime uniquement les recettes Lidl absentes de [keep]. */
    fun removeAllExcept(keep: Set<String>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            val gone = titles(db, RecipeStorageSchema.LIDL).filterKeys { it !in keep }
            db.beginTransaction()
            try {
                for (id in gone.keys) db.execSQL(RecipeStorageSchema.DELETE_RECIPE, arrayOf(id, RecipeStorageSchema.LIDL))
                carryOver(db, gone)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) rebuild()
        }.get()
    }

    /** id → titre des recettes de [sourceId] (« lidl » ou une source JSON). */
    private fun titles(db: SQLiteDatabase, sourceId: String): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery(RecipeStorageSchema.SOURCE_TITLES, arrayOf(sourceId)).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /**
     * Favoris et adaptations des recettes Lidl retirées ([gone] : id → titre), reportés sur la
     * recette Lidl restante de même titre : passer des recettes de l'appli d'origine à celles du
     * site (ou l'inverse) ne les fait pas disparaître. Les anciennes lignes restent, pour un retour
     * en arrière. Les recettes des sources JSON ne sont ni sources ni cibles de ce report : une
     * recette maison qui porte le titre d'une recette Lidl garde ses propres favoris et cuissons.
     */
    private fun carryOver(db: SQLiteDatabase, gone: Map<String, String>) {
        if (gone.isEmpty()) return
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        val usage = HashMap<String, Usage>()
        db.rawQuery("SELECT id, count, last FROM cooked", null).use { c ->
            while (c.moveToNext()) usage[c.getString(0)] = Usage(c.getInt(1), c.getLong(2))
        }
        val wanted = gone.filterKeys { it in favs || adjustments.containsKey(it) || usage.containsKey(it) }
        if (wanted.isEmpty()) return
        val byTitle = HashMap<String, String>()
        for ((id, title) in titles(db, RecipeStorageSchema.LIDL)) byTitle.putIfAbsent(RecipeAdjustments.titleKey(title), id)
        for ((id, title) in wanted) {
            val target = byTitle[RecipeAdjustments.titleKey(title)] ?: continue
            if (target == id) continue
            if (id in favs) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(target))
            usage[id]?.let { u ->
                val t = usage[target]
                val merged = Usage(u.count + (t?.count ?: 0), maxOf(u.lastMs, t?.lastMs ?: 0L))
                usage[target] = merged
                db.insertWithOnConflict("cooked", null, ContentValues().apply {
                    put("id", target)
                    put("count", merged.count)
                    put("last", merged.lastMs)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // Déplacé, pas copié : des allers-retours entre sources ne gonflent pas le compte.
                db.execSQL("DELETE FROM cooked WHERE id = ?", arrayOf(id))
                usage.remove(id)
            }
            adjustments[id]?.let { a ->
                val r = get(target) ?: return@let
                val merged = a.carriedTo(r, adjustments[target] ?: RecipeAdjustments())
                if (merged.isEmpty) return@let
                adjustments[target] = merged
                db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                    put("id", target)
                    put("json", merged.encode())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Note qu'une recette vient d'être cuisinée (une fois par cuisson). */
    fun markCooked(id: String) {
        val now = System.currentTimeMillis()
        exec.execute {
            val u = cooked[id]
            val next = Usage((u?.count ?: 0) + 1, now)
            helper.writableDatabase.insertWithOnConflict("cooked", null, ContentValues().apply {
                put("id", id)
                put("count", next.count)
                put("last", next.lastMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            cooked[id] = next
            notifyChanged()
        }
    }

    fun usage(id: String): Usage? = cooked[id]

    /** Recettes cuisinées, de la plus récente à la plus ancienne (seulement celles du catalogue). */
    fun recentIds(limit: Int = 60): List<String> {
        val known = index.entries.mapTo(HashSet()) { it.id }
        return cooked.entries.filter { it.key in known }.sortedByDescending { it.value.lastMs }.take(limit).map { it.key }
    }

    /** Recettes les plus cuisinées (à égalité, la plus récente d'abord). */
    fun mostCookedIds(limit: Int = 60): List<String> {
        val known = index.entries.mapTo(HashSet()) { it.id }
        return cooked.entries.filter { it.key in known }
            .sortedWith(compareByDescending<Map.Entry<String, Usage>> { it.value.count }.thenByDescending { it.value.lastMs })
            .take(limit).map { it.key }
    }

    /** Replace one JSON source atomically. Recipes withdrawn by the source remain if favorited. */
    fun replaceJsonSource(source: JsonRecipeSource, recipes: List<Recipe>) {
        require(recipes.all { source.owns(it.id) })
        exec.submit {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val keep = recipes.map { it.id }.toHashSet()
                val gone = ArrayList<String>()
                db.rawQuery(RecipeStorageSchema.SOURCE_IDS, arrayOf(source.origin)).use { c ->
                    while (c.moveToNext()) c.getString(0).let { if (it !in keep) gone += it }
                }
                for (id in gone) db.execSQL(RecipeStorageSchema.DELETE_UNFAVORITED_RECIPE, arrayOf(id, source.origin))
                val now = System.currentTimeMillis()
                for (r in recipes) {
                    val v = ContentValues()
                    v.put("id", r.id)
                    v.put("title", r.title)
                    v.put("json", RecipeCodec.encode(r.copy(favorite = false)))
                    v.put("updated", now)
                    v.put("source_id", source.origin)
                    check(db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            rebuild()
        }.get()
    }

    /** Photos (champ `image`) des recettes enregistrées pour la source [origin]. */
    fun imagesOf(origin: String): List<String> {
        val ids = ArrayList<String>()
        helper.readableDatabase.rawQuery(RecipeStorageSchema.SOURCE_IDS, arrayOf(origin)).use { c -> while (c.moveToNext()) ids += c.getString(0) }
        return ids.mapNotNull { get(it)?.image?.takeIf { img -> img.isNotBlank() } }
    }

    /** Explicit source removal keeps the user's favorites available offline. */
    fun removeJsonSource(source: JsonRecipeSource) {
        exec.submit {
            helper.writableDatabase.execSQL(RecipeStorageSchema.DELETE_UNFAVORITED_SOURCE, arrayOf(source.origin))
            rebuild()
        }.get()
    }

    /** Recharge l'index de recherche (après des mises à jour sans [rebuild]). */
    fun refresh() {
        exec.submit { rebuild() }.get()
    }

    fun imageFile(name: String): File? {
        if (name.isBlank()) return null
        val clean = name.substringAfterLast('/')
        for (candidate in listOf("large-$clean", clean, "small-$clean")) {
            val f = File(imagesDir, candidate)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun runInBackground(block: () -> Unit) = exec.execute(block)
}
