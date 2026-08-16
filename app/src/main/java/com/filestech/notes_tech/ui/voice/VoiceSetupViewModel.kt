package com.filestech.notes_tech.ui.voice

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.voice.SttModelStore
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.filestech.notes_tech.domain.voice.SttModelChecksumMismatchException
import com.filestech.notes_tech.domain.voice.SttModelSourceInvalidException
import com.filestech.notes_tech.domain.voice.SttModelStorageFullException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Ce que l'écran d'installation affiche.
 *
 * @param installe l'identifiant du modèle présent et conforme, ou `null`.
 * @param verificationEnCours vrai pendant la relecture d'empreinte du démarrage — plusieurs
 *   secondes sur un fichier de cinquante mégaoctets, donc quelque chose doit le dire.
 * @param progression `0..100` pendant un import, `null` sinon.
 * @param erreur la **cause** du dernier échec, jamais un message d'exception. Voir [ErreurImport].
 */
data class VoiceSetupState(
    val modeles: List<SttModel> = SttModelCatalogue.tous,
    val installe: String? = null,
    val verificationEnCours: Boolean = false,
    val progression: Int? = null,
    val erreur: ErreurImport? = null,
    val lienCopie: Boolean = false,
    /**
     * 🔴 Le nom du modèle qui vient d'être installé, **à annoncer une fois**.
     *
     * Sans cela, un import de plusieurs minutes se terminait par un simple changement de libellé
     * dans une carte — un retour si discret que l'utilisateur, qui venait de regarder une barre de
     * progression, pouvait ne pas savoir si c'était fini. La chaîne existait, traduite, et n'était
     * utilisée nulle part : une orpheline qui signalait un retour manquant, pas une chaîne en trop.
     */
    val installeAvecSucces: String? = null,
) {
    val importEnCours: Boolean get() = progression != null
}

/**
 * Pourquoi un import a échoué, **par ce que l'utilisateur peut y faire**.
 *
 * 🔴 **Un type, pas un message.** `VaultAttempt` a dû cesser de transporter `exception.message` —
 * du texte interne, non traduit — parce que l'écran le montrait tel quel dans les deux langues. Et
 * chacun de ces cas appelle un geste différent : choisir un autre fichier, libérer de la place,
 * retélécharger, réessayer. Un « échec de l'import » unique les rendrait indiscernables.
 */
enum class ErreurImport { FICHIER_INADAPTE, PLACE_INSUFFISANTE, EMPREINTE, TECHNIQUE }

@HiltViewModel
class VoiceSetupViewModel @Inject constructor(private val magasin: SttModelStore) : ViewModel() {

    private val _etat = MutableStateFlow(VoiceSetupState())
    val etat: StateFlow<VoiceSetupState> = _etat.asStateFlow()

    /**
     * L'import en vol, pour pouvoir l'annuler.
     *
     * ⚠️ Un seul à la fois : le magasin sérialise déjà les imports, mais l'écran doit **refuser**
     * le second plutôt que le faire attendre sans rien dire. Un bouton qui ne répond pas se
     * réappuie.
     */
    private var importEnVol: Job? = null

    init {
        rafraichir()
    }

    /**
     * Relit ce qui est installé.
     *
     * ⚠️ Le contrôle **complet**, avec relecture d'empreinte : c'est la question « ce binaire
     * est-il celui qu'on croit ? », et l'écran d'installation est exactement l'endroit où elle se
     * pose. Le contrôle bon marché servirait à décider d'afficher un badge, pas à annoncer que la
     * dictée est prête.
     */
    fun rafraichir() {
        viewModelScope.launch {
            _etat.update { it.copy(verificationEnCours = true) }
            val present = SttModelCatalogue.tous.firstOrNull { magasin.estInstalle(it) }
            _etat.update { it.copy(installe = present?.id, verificationEnCours = false) }
        }
    }

    fun importer(modele: SttModel, source: Uri) {
        if (importEnVol?.isActive == true) return
        importEnVol = viewModelScope.launch {
            _etat.update { it.copy(progression = 0, erreur = null) }
            try {
                magasin.importer(source = source, modele = modele) { avancement ->
                    // ⚠️ Le rappel vient du fil d'E/S. `MutableStateFlow.update` est sûr depuis
                    // n'importe quel fil ; c'est Compose qui recompose sur le bon.
                    _etat.update { it.copy(progression = (avancement.fraction * 100).toInt()) }
                }
                _etat.update {
                    it.copy(installe = modele.id, progression = null, installeAvecSucces = modele.displayName)
                }
            } catch (e: CancellationException) {
                // ⚠️ Relancée, jamais avalée — mais l'état doit repartir propre, sinon l'écran
                // resterait bloqué sur une barre de progression après un simple retour arrière.
                _etat.update { it.copy(progression = null) }
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "dictee : import du modele %s en echec", modele.id)
                _etat.update { it.copy(progression = null, erreur = classer(e)) }
            }
        }
    }

    /**
     * Annule l'import en cours.
     *
     * ⚠️ Le fichier temporaire part avec : le magasin efface sur toute sortie, annulation comprise.
     * L'écran n'a donc rien à nettoyer, et ne doit surtout pas essayer.
     */
    fun annulerImport() {
        importEnVol?.cancel()
    }

    fun desinstaller(modele: SttModel) {
        viewModelScope.launch {
            // ⚠️ `NonCancellable` : un retour arrière au mauvais moment laisserait un fichier que
            // l'utilisateur croit avoir retiré. Le geste est court et sans retour ; il va au bout.
            withContext(NonCancellable) { magasin.desinstaller(modele) }
            _etat.update { it.copy(installe = null) }
        }
    }

    fun oublierLErreur() = _etat.update { it.copy(erreur = null) }

    fun lienCopie() = _etat.update { it.copy(lienCopie = true) }

    /**
     * ⚠️⚠️ **Deux consommations distinctes, et non une qui efface les deux.**
     *
     * Elles étaient réunies. Un import qui se terminait juste après un appui sur « copier le lien »
     * posait les deux messages ; l'écran en affichait un, et l'unique fonction de consommation
     * **effaçait aussi l'autre**. Le second n'était jamais dit. Relevé par une relecture externe
     * (GPT-5.5, 2026-08-16) — sur un correctif écrit une heure plus tôt.
     */
    fun lienConsomme() = _etat.update { it.copy(lienCopie = false) }

    fun installationConsommee() = _etat.update { it.copy(installeAvecSucces = null) }

    /**
     * ⚠️ Le `when` est sur le **type**, jamais sur le texte. Un classement par message se casse à
     * la première traduction — c'est déjà écrit dans `SttErrors`, et c'est ici que ça se tient.
     */
    private fun classer(e: Throwable): ErreurImport = when (e) {
        is SttModelSourceInvalidException -> ErreurImport.FICHIER_INADAPTE
        is SttModelStorageFullException -> ErreurImport.PLACE_INSUFFISANTE
        is SttModelChecksumMismatchException -> ErreurImport.EMPREINTE
        else -> ErreurImport.TECHNIQUE
    }
}
