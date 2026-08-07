/// Purge des fichiers de modèles hérités — `<appSupport>/models/`.
///
/// Ce dossier contenait le `.task` Gemma importé à la main par l'utilisateur
/// et le cache MiniLM. Plus aucun code ne l'alimente depuis le retrait de l'IA
/// en v1.1.7, mais un utilisateur qui met à jour depuis une version ≤ 1.1.6 en
/// a toujours le contenu : **jusqu'à 530 Mo** qu'il ne voit pas, qu'aucun
/// écran ne mentionne, et qu'il ne peut effacer qu'en vidant les données de
/// l'application — ce qui détruirait aussi ses notes.
///
/// DEUX APPELANTS, et c'est délibéré :
///   - le démarrage, une seule fois, gardé par une préférence ;
///   - le mode panique, à chaque exécution, qui ne doit dépendre d'aucune
///     purge antérieure ayant réussi.
///
/// La logique vit ici plutôt que dupliquée aux deux endroits. Elle l'était :
/// deux copies du même `Directory('$path/models').delete(recursive: true)`,
/// dans `main.dart` et dans `PanicService`. Une divergence entre les deux
/// n'aurait été visible nulle part — et celle du mode panique porte une
/// promesse de sécurité.
///
/// ⚠️ CIBLE UNIQUEMENT `models/`. Le modèle de dictée vocale vit dans
/// `<appSupport>/stt/` (cf. `files_tech_voice/stt_model_downloader.dart`) et
/// ne doit JAMAIS être touché ici : l'utilisateur a dû le télécharger puis
/// l'importer à la main, et se tromper de dossier le lui ferait recommencer
/// sans explication. Un test vérifie explicitement que `stt/` survit.
library;

import 'dart:io';

import 'package:path_provider/path_provider.dart';

class LegacyModelFiles {
  LegacyModelFiles._();

  /// Nom du dossier purgé, exposé pour que les tests visent exactement la
  /// même cible que la production plutôt qu'une chaîne recopiée.
  static const String directoryName = 'models';

  /// Supprime `<appSupport>/models/` s'il existe.
  ///
  /// Retourne `true` quand la purge aboutit — **y compris lorsqu'il n'y avait
  /// rien à supprimer**, qui est le cas normal d'une installation neuve.
  /// Retourne `false` si l'I/O a échoué : l'appelant du démarrage s'en sert
  /// pour ne PAS poser son drapeau et réessayer au boot suivant.
  ///
  /// Ne lève jamais. Le mode panique doit aller au bout quoi qu'il arrive.
  static Future<bool> purge() async {
    try {
      final dir = await getApplicationSupportDirectory();
      final models = Directory('${dir.path}/$directoryName');
      if (await models.exists()) {
        await models.delete(recursive: true);
      }
      return true;
    } catch (_) {
      return false;
    }
  }
}
