package app.toque.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.toque.Logbook
import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Photos de recettes, réduites (800 px de large au plus) et enregistrées sous le nom de fichier
 * de leur URL, là où [RecipeRepo.imageFile] les cherche. Servent aux recettes Lidl
 * ([OnlineSync]) et à celles des sources JSON ([JsonSourceSync]).
 */
object RecipePhotos {
    const val MAX_WIDTH = 800
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Décode [bytes], réduit l'image et l'enregistre dans [dir] sous le nom de fichier de [url]. */
    fun store(dir: File, url: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("image illisible"))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, bmp.height * MAX_WIDTH / bmp.width, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val name = url.substringAfterLast('/').substringBefore('?')
        val tmp = File(dir, "$name.part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(File(dir, name))) tmp.delete()
    }

    /**
     * Télécharge les photos manquantes d'une source JSON ([urls] : le champ `image` de ses
     * recettes). HTTPS seulement, comme le catalogue. Un échec est consigné et n'empêche pas la
     * synchro ; après 5 échecs de suite, on s'arrête (réessayé à la synchro suivante). Hors du fil
     * principal. Renvoie le nombre de photos enregistrées.
     */
    fun fetchMissing(repo: RecipeRepo, urls: Collection<String>, log: Logbook): Int {
        val missing = urls.filter { it.startsWith("https://", ignoreCase = true) && repo.imageFile(it) == null }.distinct()
        var saved = 0
        var inARow = 0
        for (url in missing) {
            try {
                store(repo.imagesDir, url, httpsBytes(url))
                repo.photoSaved(url.substringAfterLast('/').substringBefore('?'))
                saved++
                inARow = 0
            } catch (e: Exception) {
                log.write("source-json", "photo $url : ${e.message}")
                if (++inARow >= 5) break
            }
        }
        return saved
    }

    /** GET HTTPS, 8 Mio au plus. HttpURLConnection ne suit pas une redirection vers http. */
    private fun httpsBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Toque JSON recipes")
            val code = c.responseCode
            if (!c.url.protocol.equals("https", ignoreCase = true)) throw IOException(tr("La source doit rester en HTTPS."))
            if (code != 200) throw IOException(tr("réponse %d du serveur", code))
            if (c.contentLength > MAX_BYTES) throw IOException(tr("réponse trop grande"))
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException(tr("réponse trop grande"))
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
