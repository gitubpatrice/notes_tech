package com.filestech.notes_tech.ui.common

import androidx.compose.ui.unit.dp

/**
 * La hauteur maximale d'une liste de choix posée dans une feuille modale.
 *
 * ## Pourquoi une constante partagée plutôt qu'une valeur par feuille
 *
 * Les deux feuilles de l'éditeur — choisir un titre à lier, choisir un dossier — portaient chacune
 * une constante privée du même nom et du même rôle, à deux valeurs différentes (320 dp et 360 dp),
 * sans qu'aucune ligne n'explique l'écart. Aucun bug : juste deux réponses à la même question, et la
 * garantie qu'une troisième feuille en inventerait une troisième.
 *
 * ⚠️ Ce qu'il faut borner, c'est la **contrainte reçue par la liste**, pas son apparence : un
 * `LazyColumn` mesuré sous une hauteur infinie plante. La valeur exacte est un choix d'ergonomie —
 * assez pour montrer plusieurs entrées, assez peu pour que le champ de saisie et le clavier restent
 * visibles au-dessus.
 *
 * Relevé par l'audit de cohérence du 2026-08-15.
 */
internal val HAUTEUR_MAXIMALE_LISTE_DE_CHOIX = 320.dp
