/// Interface d'un encodeur de texte → vecteur dense.
///
/// Toutes les implémentations doivent retourner des vecteurs L2-normalisés
/// de dimension stable, identifiés par un `modelId`.
/// Les implémentations futures (MiniLmEmbedder via ONNX) doivent rester
/// compatibles avec ce contrat sans casser la table `note_embeddings`
/// (le `modelId` est stocké et permet d'invalider en lot lors d'un swap).
library;

import 'dart:typed_data';

abstract interface class EmbeddingProvider {
  /// Identifiant stable du modèle. Stocké en base ; utilisé pour invalider
  /// les vecteurs à chaque changement d'implémentation.
  String get modelId;

  /// Dimension du vecteur produit. Stable pour un modèle donné.
  int get dim;

  /// Encode un texte en vecteur dense, L2-normalisé.
  ///
  /// Implémentations longues (>16 ms) doivent tourner en isolate
  /// — c'est la responsabilité de l'appelant.
  Float32List embed(String text);

  /// Initialisation paresseuse optionnelle (chargement modèle ONNX, etc.).
  /// L'implémentation par défaut ne fait rien.
  Future<void> warmUp() async {}

  /// Libération des ressources (sessions ONNX, etc.).
  Future<void> dispose() async {}
}
