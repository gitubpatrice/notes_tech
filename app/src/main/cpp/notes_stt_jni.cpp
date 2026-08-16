// Le pont entre Kotlin et whisper.cpp.
//
// ## Ce que ce fichier fait, et ce qu'il refuse de faire
//
// Il expose le strict nécessaire : ouvrir un modèle, transcrire un bloc d'échantillons, lire le
// résultat, refermer. Rien d'autre ne franchit la frontière.
//
// En particulier, **il ne lit aucun fichier audio**. Le greffon dont viennent les sources embarquait
// `dr_wav.h`, un analyseur WAV généraliste de 356 Ko, et lui donnait un chemin. Ici le décodage a
// lieu en Kotlin, dans `WavPcm16` — déjà écrit, déjà vérifié octet par octet, et qui n'accepte que
// le seul format que la capture produit. Analyser un format de fichier en C, sur un fichier venu du
// disque, c'est une surface d'attaque pour un besoin qu'on n'a pas.
//
// ## ⚠️ La règle qui gouverne tout ce fichier
//
// **Rien ne doit jamais traverser la frontière JNI sous forme d'exception C++.** Une exception qui
// remonte dans la machine virtuelle termine le processus, sans trace exploitable. Chaque fonction
// rend donc un code ou une valeur nulle, et c'est Kotlin qui décide de l'erreur à lever — là où la
// hiérarchie `SttException` existe, et là où l'on sait quoi proposer à l'utilisateur.

#include <jni.h>

#include <atomic>
#include <cstring>
#include <new>
#include <string>

#include "whisper.h"

namespace {

/**
 * Ce que Kotlin détient sous forme de `Long`.
 *
 * ⚠️ Le drapeau d'arrêt vit ICI et non dans une variable globale : deux transcriptions ne peuvent
 * pas se dérouler en même temps aujourd'hui — un verrou côté Kotlin s'en assure — mais un drapeau
 * global ferait de cette garantie une condition tacite de la correction du code natif. Une
 * interruption s'adresse à un contexte, pas au processus.
 */
struct ContexteStt {
    whisper_context * moteur = nullptr;
    std::atomic<bool> arret{false};
};

ContexteStt * depuisPoignee(jlong poignee) {
    return reinterpret_cast<ContexteStt *>(poignee);
}

/** Vrai quand l'appelant a demandé l'arrêt. whisper interroge ceci entre deux calculs. */
bool arretDemande(void * donnees) {
    auto * contexte = static_cast<ContexteStt *>(donnees);
    return contexte != nullptr && contexte->arret.load(std::memory_order_relaxed);
}

/**
 * Une chaîne Java en UTF-8, ou une chaîne vide si la conversion échoue.
 *
 * ⚠️ `GetStringUTFChars` peut rendre `nullptr` en cas de manque de mémoire. Le déréférencer serait
 * le genre de plantage qui n'arrive qu'aux appareils déjà en difficulté, c'est-à-dire exactement
 * ceux que la dictée sollicite le plus.
 */
std::string enUtf8(JNIEnv * env, jstring texte) {
    if (texte == nullptr) return {};
    const char * brut = env->GetStringUTFChars(texte, nullptr);
    if (brut == nullptr) return {};
    std::string sortie(brut);
    env->ReleaseStringUTFChars(texte, brut);
    return sortie;
}

} // namespace

extern "C" {

/**
 * Charge un modèle. Rend une poignée, ou `0` si le chargement a échoué.
 *
 * ⚠️ `use_gpu = false` : whisper.cpp n'a pas de dorsale GPU utilisable ici, et lui en demander une
 * ne ferait qu'ajouter un chemin d'échec silencieux au démarrage.
 */
JNIEXPORT jlong JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_ouvrir(JNIEnv * env, jobject, jstring cheminModele) {
    const std::string chemin = enUtf8(env, cheminModele);
    if (chemin.empty()) return 0;

    auto * contexte = new (std::nothrow) ContexteStt();
    if (contexte == nullptr) return 0;

    whisper_context_params parametres = whisper_context_default_params();
    parametres.use_gpu = false;

    contexte->moteur = whisper_init_from_file_with_params(chemin.c_str(), parametres);
    if (contexte->moteur == nullptr) {
        // ⚠️ Le contexte est rendu ici même. Sans cela, chaque tentative de chargement d'un modèle
        // illisible — le cas d'un fichier corrompu, donc le cas qui se répète — laisserait une fuite.
        delete contexte;
        return 0;
    }
    return reinterpret_cast<jlong>(contexte);
}

/**
 * Transcrit [echantillons]. Rend `0` en cas de succès, un code non nul sinon.
 *
 * @param langue code ISO 639-1, ou une chaîne vide pour laisser le modèle décider.
 *
 * ⚠️⚠️ **Les échantillons sont lus sans copie** (`GetPrimitiveArrayCritical` n'est PAS utilisé : la
 * transcription dure plusieurs secondes, et suspendre le ramasse-miettes pendant tout ce temps
 * gèlerait l'application). `GetFloatArrayElements` peut copier ; c'est le prix à payer, et il se
 * mesure en mégaoctets une fois, pas en gel d'interface.
 */
JNIEXPORT jint JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_transcrire(
    JNIEnv * env, jobject, jlong poignee, jfloatArray echantillons, jstring langue, jint fils) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr || echantillons == nullptr) return -1;

    const jsize nombre = env->GetArrayLength(echantillons);
    if (nombre <= 0) return -2;

    jfloat * donnees = env->GetFloatArrayElements(echantillons, nullptr);
    if (donnees == nullptr) return -3;

    const std::string codeLangue = enUtf8(env, langue);

    whisper_full_params parametres = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // ⚠️ Toute impression est coupée : whisper écrit sinon sur la sortie standard, qui part dans
    // logcat. Le texte transcrit est le contenu d'une note — il n'a rien à faire dans un journal
    // système lisible par `adb`, ni dans un rapport d'incident.
    parametres.print_realtime   = false;
    parametres.print_progress   = false;
    parametres.print_timestamps = false;
    parametres.print_special    = false;
    parametres.translate        = false;
    parametres.no_context       = true;
    parametres.single_segment   = false;
    parametres.n_threads        = fils > 0 ? fils : 1;
    // Vide ⇒ détection automatique, c'est la convention de `whisper.h`.
    parametres.language         = codeLangue.empty() ? nullptr : codeLangue.c_str();
    parametres.detect_language  = codeLangue.empty();

    // 🔴 L'interruption. Sans elle, une transcription annulée continue de consommer le processeur
    // pendant plusieurs secondes sur un appareil que l'utilisateur croit avoir libéré — et, en mode
    // panique, elle tiendrait le fichier audio ouvert pendant que la séquence tente de l'effacer.
    contexte->arret.store(false, std::memory_order_relaxed);
    parametres.abort_callback           = arretDemande;
    parametres.abort_callback_user_data = contexte;

    const int resultat = whisper_full(contexte->moteur, parametres, donnees, nombre);

    // ⚠️ `JNI_ABORT` : le tableau n'a pas été modifié, donc rien à recopier vers Java. Le mode par
    // défaut recopierait plusieurs mégaoctets pour rien.
    env->ReleaseFloatArrayElements(echantillons, donnees, JNI_ABORT);

    if (contexte->arret.load(std::memory_order_relaxed)) return -4;
    return resultat;
}

/** Demande l'arrêt de la transcription en cours. Sans effet s'il n'y en a pas. */
JNIEXPORT void JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_demanderArret(JNIEnv *, jobject, jlong poignee) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte != nullptr) contexte->arret.store(true, std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_nombreDeSegments(JNIEnv *, jobject, jlong poignee) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr) return 0;
    return whisper_full_n_segments(contexte->moteur);
}

JNIEXPORT jstring JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_texteDuSegment(
    JNIEnv * env, jobject, jlong poignee, jint index) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr) return env->NewStringUTF("");
    if (index < 0 || index >= whisper_full_n_segments(contexte->moteur)) return env->NewStringUTF("");

    const char * texte = whisper_full_get_segment_text(contexte->moteur, index);
    return env->NewStringUTF(texte != nullptr ? texte : "");
}

/**
 * Le début du segment, **en millisecondes**.
 *
 * ⚠️ whisper compte en centièmes de seconde. La conversion a lieu ici, une fois, plutôt que chez
 * chaque appelant : le reste du dépôt compte en millisecondes, et une unité qui change de sens en
 * franchissant une frontière finit toujours par être lue dans la mauvaise.
 */
JNIEXPORT jlong JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_debutDuSegment(JNIEnv *, jobject, jlong poignee, jint index) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr) return 0;
    if (index < 0 || index >= whisper_full_n_segments(contexte->moteur)) return 0;
    return static_cast<jlong>(whisper_full_get_segment_t0(contexte->moteur, index)) * 10;
}

/** La fin du segment, en millisecondes. Voir la note de `debutDuSegment`. */
JNIEXPORT jlong JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_finDuSegment(JNIEnv *, jobject, jlong poignee, jint index) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr) return 0;
    if (index < 0 || index >= whisper_full_n_segments(contexte->moteur)) return 0;
    return static_cast<jlong>(whisper_full_get_segment_t1(contexte->moteur, index)) * 10;
}

/** Le code de langue reconnu, ou une chaîne vide. */
JNIEXPORT jstring JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_langueDetectee(JNIEnv * env, jobject, jlong poignee) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr || contexte->moteur == nullptr) return env->NewStringUTF("");

    const int identifiant = whisper_full_lang_id(contexte->moteur);
    if (identifiant < 0) return env->NewStringUTF("");

    const char * code = whisper_lang_str(identifiant);
    return env->NewStringUTF(code != nullptr ? code : "");
}

/**
 * Rend les ressources natives. Idempotent côté Kotlin, qui remet sa poignée à zéro.
 *
 * ⚠️⚠️ **Un modèle chargé occupe des dizaines de mégaoctets de mémoire native**, que le
 * ramasse-miettes de la machine virtuelle ne voit pas et ne réclamera jamais. Oublier cet appel ne
 * produit pas une fuite discrète : il produit une application que le système tue pour excès de
 * mémoire, sur les appareils les plus modestes d'abord.
 */
JNIEXPORT void JNICALL
Java_com_filestech_notes_1tech_data_voice_WhisperNatif_fermer(JNIEnv *, jobject, jlong poignee) {
    ContexteStt * contexte = depuisPoignee(poignee);
    if (contexte == nullptr) return;
    if (contexte->moteur != nullptr) whisper_free(contexte->moteur);
    delete contexte;
}

} // extern "C"
