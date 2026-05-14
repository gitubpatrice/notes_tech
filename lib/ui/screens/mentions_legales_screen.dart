/// Écran « Mentions légales » — rendu Markdown des fichiers
/// `assets/legal/PRIVACY.{fr,en}.md` et `assets/legal/TERMS.{fr,en}.md`,
/// sélection automatique selon la locale active.
///
/// v1.0 : utilise `flutter_markdown` + `rootBundle.loadString` pour charger
/// la version localisée de la politique de confidentialité et des conditions
/// d'utilisation. Le rendu Markdown est `selectable: true` pour copy/paste.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../l10n/app_localizations.dart';

class MentionsLegalesScreen extends StatelessWidget {
  const MentionsLegalesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final isEn = Localizations.localeOf(context).languageCode == 'en';
    final privacyAsset = isEn
        ? 'assets/legal/PRIVACY.en.md'
        : 'assets/legal/PRIVACY.fr.md';
    final termsAsset = isEn
        ? 'assets/legal/TERMS.en.md'
        : 'assets/legal/TERMS.fr.md';

    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Semantics(header: true, child: Text(t.legalTitle)),
          bottom: TabBar(
            tabs: [
              Tab(text: t.legalTabPrivacy),
              Tab(text: t.legalTabTerms),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _MarkdownAssetView(
              key: ValueKey(privacyAsset),
              asset: privacyAsset,
            ),
            _MarkdownAssetView(key: ValueKey(termsAsset), asset: termsAsset),
          ],
        ),
      ),
    );
  }
}

class _MarkdownAssetView extends StatelessWidget {
  const _MarkdownAssetView({super.key, required this.asset});
  final String asset;

  /// P5 v1.1.0 — cache process-wide des assets Markdown chargés. Avant :
  /// chaque switch d'onglet TabBarView ou de locale FR/EN re-déclenchait
  /// `rootBundle.loadString` (~5-20 Ko I/O + reparse Markdown intégral).
  /// Désormais : `static final Map` retient le contenu pour la durée de
  /// l'app — `PRIVACY.*` et `TERMS.*` sont immuables côté assets.
  static final Map<String, String> _assetCache = <String, String>{};

  Future<String> _load() async {
    final cached = _assetCache[asset];
    if (cached != null) return cached;
    final raw = await rootBundle.loadString(asset);
    _assetCache[asset] = raw;
    return raw;
  }

  Future<void> _onTapLink(BuildContext context, String? href) async {
    if (href == null || href.isEmpty) return;
    final uri = Uri.tryParse(href);
    if (uri == null) return;
    // Sécurité : on n'ouvre QUE http(s) et mailto via l'app système.
    if (uri.scheme != 'http' &&
        uri.scheme != 'https' &&
        uri.scheme != 'mailto') {
      return;
    }
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // Best-effort : si aucun navigateur/client mail, l'utilisateur peut
      // lire le lien dans le markdown rendu (selectable: true).
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<String>(
      // `key: ValueKey(asset)` au-dessus force un rebuild + reload du
      // FutureBuilder quand l'utilisateur change de locale dans Settings.
      // P5 v1.1.0 — `_load()` cache le contenu déjà chargé (assets immuables).
      future: _load(),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snap.hasError || !snap.hasData) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                AppLocalizations.of(context).commonErrorWith('${snap.error}'),
                textAlign: TextAlign.center,
              ),
            ),
          );
        }
        return Markdown(
          data: snap.data!,
          selectable: true,
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
          onTapLink: (text, href, title) => _onTapLink(context, href),
        );
      },
    );
  }
}
