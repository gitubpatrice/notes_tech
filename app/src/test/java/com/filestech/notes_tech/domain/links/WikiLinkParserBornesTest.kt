package com.filestech.notes_tech.domain.links

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Ce que fait le releveur de liens quand une note DÉPASSE.**
 *
 * Ligne `backlinks_service.dart` de `docs/05-PARITE.md`. Le relevé du 2026-08-16 avait établi que
 * les plafonds concordent **au chiffre près** — `CONTENT_SCAN_LIMIT = 50_000` contre
 * `noteContentBacklinksLimit = 50000`, `MAX_LINKS_PER_NOTE = 256` contre `_maxLinksPerNote = 256`.
 * La ligne notait ce qui restait : *« vérifier le comportement sur une note qui dépasse »*.
 *
 * ## ⚠️ Pourquoi deux constantes égales ne suffisent pas
 *
 * Le Dart tronque par `substring`, puis parcourt les correspondances et **sort de la boucle** quand
 * il a retenu 256 liens. Le test est fait **avant** de traiter la correspondance : un doublon ou un
 * titre vide ne consomme donc **pas** le budget. Le portage obtient le même résultat autrement, par
 * un `distinctBy` **suivi** d'un `take`, et son commentaire l'explique.
 *
 * Deux chemins différents vers la même règle : c'est exactement la situation où un raisonnement
 * juste et un comportement faux se ressemblent. Les cas ci-dessous mesurent le comportement.
 *
 * ⚠️ Les entrées sont **engendrées** plutôt que lues dans `extract.tsv` : un vecteur de 50 000
 * caractères sur une ligne de TSV serait illisible et impossible à relire. C'est la raison pour
 * laquelle ces bornes n'avaient jamais été couvertes.
 */
@DisplayName("Bornes du releveur de liens")
class WikiLinkParserBornesTest {

    // ── La fenêtre de balayage ───────────────────────────────────────────────────────────────────

    /**
     * 🔴 Un lien qui **finit exactement** au dernier caractère analysé est retenu.
     *
     * La borne est un `substring(0, 50_000)` : le caractère d'indice 49 999 est le dernier gardé.
     * Un lien dont le `]]` s'y termine tient donc tout entier dans la fenêtre.
     */
    @Test
    @DisplayName("un lien qui finit au tout dernier caractère analysé est retenu")
    fun lienCollePileALaBorne() {
        val lien = "[[Fin]]"
        val bourrage = "x".repeat(WikiLinkParser.CONTENT_SCAN_LIMIT - lien.length)
        val contenu = bourrage + lien + "[[Apres]]"

        val releves = WikiLinkParser.extract(contenu)

        assertThat(releves.map(ExtractedLink::title)).containsExactly("Fin")
        assertThat(releves.single().position).isEqualTo(bourrage.length)
    }

    /**
     * 🔴🔴 **Un lien à cheval sur la frontière n'est pas un lien tronqué : il n'est pas un lien.**
     *
     * Le `]]` tombe hors de la fenêtre, l'expression ne trouve donc rien. Ce qu'il faut vérifier,
     * c'est qu'il ne reste **aucune** trace — ni un titre amputé, ni une entrée au titre vide.
     *
     * ⚠️ Le témoin est le **même lien décalé d'un caractère** vers la gauche : sans lui, un
     * releveur qui ne rendrait jamais rien passerait ce cas aussi.
     */
    @Test
    @DisplayName("un lien à cheval sur la frontière disparaît entièrement")
    fun lienACheval() {
        val lien = "[[Coupe]]"
        val trop = "x".repeat(WikiLinkParser.CONTENT_SCAN_LIMIT - lien.length + 1)
        assertThat(WikiLinkParser.extract(trop + lien)).isEmpty()

        val juste = "x".repeat(WikiLinkParser.CONTENT_SCAN_LIMIT - lien.length)
        assertThat(WikiLinkParser.extract(juste + lien).map(ExtractedLink::title))
            .containsExactly("Coupe")
    }

    // ── Le plafond de liens ──────────────────────────────────────────────────────────────────────

    /**
     * 🔴 **257 liens distincts : les 256 PREMIERS sont retenus, pas les derniers.**
     *
     * Le Dart sort de la boucle ; le portage prend les 256 premiers d'une séquence. Les deux gardent
     * le début du texte. Un portage qui garderait la fin — un `takeLast`, une table qui écrase —
     * rendrait le même **nombre** de liens et les mauvais.
     */
    @Test
    @DisplayName("au-delà du plafond, ce sont les premiers liens qui sont gardés")
    fun plafondGardeLesPremiers() {
        val contenu = (0 until WikiLinkParser.MAX_LINKS_PER_NOTE + 1)
            .joinToString(" ") { "[[note-$it]]" }

        val releves = WikiLinkParser.extract(contenu)

        assertThat(releves).hasSize(WikiLinkParser.MAX_LINKS_PER_NOTE)
        assertThat(releves.first().title).isEqualTo("note-0")
        assertThat(releves.last().title).isEqualTo("note-${WikiLinkParser.MAX_LINKS_PER_NOTE - 1}")
    }

    /**
     * 🔴🔴 **Un doublon ne consomme pas le budget**, et c'est le point le plus fragile de la
     * transposition.
     *
     * Côté Dart, le `break` est testé **avant** de traiter la correspondance : une répétition passe
     * par `continue` sans faire avancer le compteur. Côté portage, `distinctBy` s'applique **avant**
     * `take`. Deux mécanismes différents, une seule règle — et un portage qui aurait mis le `take`
     * en premier rendrait ici **une poignée de liens au lieu de 256**, sans rien signaler.
     */
    @Test
    @DisplayName("cent répétitions d'un même titre ne consomment pas le plafond")
    fun doublonsNeConsommentPasLePlafond() {
        val repetitions = (0 until 100).joinToString(" ") { "[[note-0]]" }
        val distincts = (0 until WikiLinkParser.MAX_LINKS_PER_NOTE)
            .joinToString(" ") { "[[note-$it]]" }

        val releves = WikiLinkParser.extract("$repetitions $distincts")

        assertThat(releves).hasSize(WikiLinkParser.MAX_LINKS_PER_NOTE)
        assertThat(releves.last().title).isEqualTo("note-${WikiLinkParser.MAX_LINKS_PER_NOTE - 1}")
    }

    /**
     * ⚠️ Le jumeau du cas précédent : **un titre vide ne consomme pas le budget non plus**.
     *
     * `[[   ]]` est une correspondance de l'expression, rejetée après élagage. Le Dart passe par
     * `continue`, le portage par un `mapNotNull` placé avant le `take`. Un portage qui compterait
     * les paires de crochets rencontrées rendrait **zéro** lien ici.
     */
    @Test
    @DisplayName("trois cents titres vides ne consomment pas le plafond")
    fun titresVidesNeConsommentPasLePlafond() {
        val vides = (0 until 300).joinToString(" ") { "[[   ]]" }
        val vrais = (0 until 5).joinToString(" ") { "[[note-$it]]" }

        val releves = WikiLinkParser.extract("$vides $vrais")

        assertThat(releves.map(ExtractedLink::title))
            .containsExactly("note-0", "note-1", "note-2", "note-3", "note-4")
            .inOrder()
    }

    // ── La borne portée par l'expression ─────────────────────────────────────────────────────────

    /**
     * ⚠️⚠️ **Le KDoc affirme « vérifié » ; ce cas le vérifie.**
     *
     * La longueur maximale d'un titre — 200 caractères — n'est pas contrôlée après coup, elle est
     * dans l'expression (`{1,200}`). Au-delà, il n'y a **pas de lien tronqué, il n'y a pas de
     * lien**. Le témoin est le titre de 200 caractères exactement, qui doit passer : sans lui, une
     * expression cassée qui ne rendrait jamais rien satisferait la moitié de ce cas.
     */
    @Test
    @DisplayName("un titre de 201 caractères n'est pas un lien, un de 200 en est un")
    fun borneDeLongueurDuTitre() {
        assertThat(WikiLinkParser.extract("[[" + "a".repeat(201) + "]]")).isEmpty()

        val limite = "a".repeat(200)
        assertThat(WikiLinkParser.extract("[[$limite]]").map(ExtractedLink::title))
            .containsExactly(limite)
    }
}
