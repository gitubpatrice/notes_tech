/// Read-only rendering of a note's Markdown content.
///
/// The editor stores raw Markdown; this widget is what turns it into
/// headings, lists, emphasis and links. Two rules are specific to Notes Tech:
///
///  - `[[Title]]` links between notes are rendered as tappable anchors,
///    matched with the very pattern [BacklinksService] indexes, so the
///    preview and the links panel always agree on what a link is;
///  - images are NEVER loaded. The package's default image builder reads
///    network URLs, assets and local files; a note is untrusted input and
///    the app has no network, so only the alt text is shown.
library;

import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:url_launcher/url_launcher.dart';

import '../../services/backlinks_service.dart';

/// Opens a link found in rendered Markdown with the system handler, only for
/// `http`, `https` and `mailto`. Any other scheme (`file`, `content`,
/// `intent`, custom ones) is ignored. Shared with the legal notices screen.
Future<void> launchExternalMarkdownLink(String? href) async {
  if (href == null || href.isEmpty) return;
  final uri = Uri.tryParse(href);
  if (uri == null) return;
  if (uri.scheme != 'http' && uri.scheme != 'https' && uri.scheme != 'mailto') {
    return;
  }
  try {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {
    // Best effort: without a browser or mail client the link stays readable
    // in the rendered text.
  }
}

/// Inline syntax for `[[Title]]`, emitted as an `<a>` whose href uses
/// [scheme] so a tap can be told apart from a web link.
class WikiLinkSyntax extends md.InlineSyntax {
  WikiLinkSyntax()
    : super(BacklinksService.linkPatternSource, startCharacter: _openBracket);

  static const int _openBracket = 0x5B; // '['

  /// Href scheme of note links. Not a registered URI scheme: it never leaves
  /// the widget, [NoteMarkdownPreview] intercepts it.
  static const String scheme = 'notes-tech-link';

  /// Title carried by an href built by this syntax, or null for any other
  /// link.
  static String? titleFromHref(String? href) {
    const prefix = '$scheme:';
    if (href == null || !href.startsWith(prefix)) return null;
    return Uri.decodeComponent(href.substring(prefix.length));
  }

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    final title = match[1]!.trim();
    if (title.isEmpty) {
      // `[[   ]]` is not a link for the indexer either: keep it as text.
      parser.addNode(md.Text(match[0]!));
      return true;
    }
    final anchor = md.Element.text('a', title);
    anchor.attributes['href'] = '$scheme:${Uri.encodeComponent(title)}';
    parser.addNode(anchor);
    return true;
  }
}

class NoteMarkdownPreview extends StatelessWidget {
  const NoteMarkdownPreview({
    super.key,
    required this.data,
    required this.emptyText,
    required this.onOpenNoteLink,
  });

  /// Raw Markdown to render.
  final String data;

  /// Shown instead of an empty render when [data] holds only whitespace.
  final String emptyText;

  /// Called with the title of a tapped `[[Title]]` link.
  final ValueChanged<String> onOpenNoteLink;

  static final List<md.InlineSyntax> _inlineSyntaxes = [WikiLinkSyntax()];

  void _onTapLink(String? href) {
    final title = WikiLinkSyntax.titleFromHref(href);
    if (title != null) {
      onOpenNoteLink(title);
      return;
    }
    launchExternalMarkdownLink(href);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (data.trim().isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Text(
          emptyText,
          style: theme.textTheme.bodyLarge?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      );
    }
    return Markdown(
      data: data,
      padding: const EdgeInsets.symmetric(vertical: 12),
      inlineSyntaxes: _inlineSyntaxes,
      styleSheet: MarkdownStyleSheet.fromTheme(
        theme,
      ).copyWith(p: theme.textTheme.bodyLarge),
      onTapLink: (text, href, title) => _onTapLink(href),
      imageBuilder: (uri, title, alt) => Text(
        (alt != null && alt.isNotEmpty) ? alt : uri.toString(),
        style: theme.textTheme.bodyMedium?.copyWith(
          fontStyle: FontStyle.italic,
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
