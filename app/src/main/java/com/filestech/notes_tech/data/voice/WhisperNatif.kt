package com.filestech.notes_tech.data.voice

/**
 * La frontière avec `libnotes_stt.so`, et **rien d'autre**.
 *
 * ## Pourquoi cette classe ne fait rien
 *
 * Elle ne décide rien, ne lève rien, n'interprète aucun code de retour. Elle est la transcription
 * littérale de `app/src/main/cpp/notes_stt_jni.cpp`, et c'est sa seule utilité : quand la signature
 * d'une fonction native change, **la compilation ne dit rien** — l'erreur arrive à l'exécution, sous
 * la forme d'un `UnsatisfiedLinkError` au premier appel, ou pire, d'une lecture de mémoire décalée.
 * Concentrer la frontière en un fichier de vingt lignes, en face du `.cpp`, est ce qui rend cette
 * correspondance vérifiable à l'œil.
 *
 * ⚠️⚠️ **Toute modification ici doit être faite dans le même commit que celle du `.cpp`.** Un nom de
 * paquet ou de classe qui change casse le décorage JNI (`com_filestech_notes_1tech_data_voice_…`)
 * sans qu'aucun outil ne le signale.
 *
 * ## 🔴 Une poignée est un pointeur natif
 *
 * `Long` ici veut dire « adresse mémoire ». Le ramasse-miettes ne la connaît pas, ne la suit pas, et
 * ne la libérera jamais : [fermer] est la **seule** façon de rendre les dizaines de mégaoctets
 * qu'occupe un modèle chargé. Une poignée utilisée après [fermer] lit de la mémoire libérée. C'est
 * exactement pour cela que rien hors de [WhisperStt] ne doit toucher cette classe.
 */
internal class WhisperNatif {

    /** Rend une poignée, ou `0` si le modèle n'a pas pu être chargé. */
    external fun ouvrir(cheminModele: String): Long

    /**
     * Rend `0` en cas de succès. `-4` signale une interruption demandée ; les autres valeurs
     * négatives sont des arguments invalides, les positives viennent du moteur.
     */
    external fun transcrire(poignee: Long, echantillons: FloatArray, langue: String, fils: Int): Int

    external fun demanderArret(poignee: Long)

    external fun nombreDeSegments(poignee: Long): Int

    external fun texteDuSegment(poignee: Long, index: Int): String

    /** En **millisecondes** — la conversion depuis les centièmes de seconde a lieu côté natif. */
    external fun debutDuSegment(poignee: Long, index: Int): Long

    /** En millisecondes. */
    external fun finDuSegment(poignee: Long, index: Int): Long

    external fun langueDetectee(poignee: Long): String

    external fun fermer(poignee: Long)

    companion object {
        /** Le code d'arrêt volontaire, rendu par [transcrire]. Voir `notes_stt_jni.cpp`. */
        const val CODE_ARRET = -4

        /**
         * Charge la bibliothèque, et dit si elle est là.
         *
         * ⚠️ **`System.loadLibrary` lève `UnsatisfiedLinkError`, une `Error` et non une
         * `Exception`** : elle traverse un `catch (e: Exception)` sans être vue. Le cas n'est pas
         * théorique — une architecture absente de l'APK, un découpage par ABI mal appliqué, et
         * c'est l'application entière qui tombe au lieu d'afficher « la dictée n'est pas
         * disponible ». D'où ce chargement unique, gardé, dont le résultat est un booléen.
         */
        val disponible: Boolean by lazy {
            try {
                System.loadLibrary("notes_stt")
                true
            } catch (e: UnsatisfiedLinkError) {
                timber.log.Timber.e(e, "dictee : bibliotheque native introuvable")
                false
            }
        }
    }
}
