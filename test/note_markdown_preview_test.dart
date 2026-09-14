import 'package:flutter_test/flutter_test.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:notes_tech/services/backlinks_service.dart';
import 'package:notes_tech/ui/widgets/note_markdown_preview.dart';

/// Renders [source] the way the preview parses it.
String _render(String source) => md.markdownToHtml(
  source,
  inlineSyntaxes: [WikiLinkSyntax()],
  extensionSet: md.ExtensionSet.gitHubFlavored,
);

void main() {
  group('WikiLinkSyntax', () {
    test('renders [[Title]] as a note link', () {
      final html = _render('See [[Note A]] now.');
      expect(
        html,
        contains('<a href="${WikiLinkSyntax.scheme}:Note%20A">Note A</a>'),
      );
    });

    test('links exactly what the indexer extracts', () {
      const content = 'One [[Été à Paris]], two [[ spaced ]], [[a]b]] not.';
      final indexed = BacklinksService.extractFromContent(
        content,
      ).map((l) => l.title).toList();
      final rendered = RegExp('<a href="${WikiLinkSyntax.scheme}:([^"]+)">')
          .allMatches(_render(content))
          .map((m) {
            return WikiLinkSyntax.titleFromHref(
              '${WikiLinkSyntax.scheme}:${m[1]}',
            );
          })
          .toList();
      expect(rendered, indexed);
    });

    test('leaves links inside code untouched', () {
      final html = _render(
        'Inline `[[Not a link]]` and\n\n```\n[[Nor this]]\n```',
      );
      expect(html, isNot(contains(WikiLinkSyntax.scheme)));
    });

    test('keeps a blank [[   ]] as plain text', () {
      final html = _render('Empty [[   ]] here.');
      expect(html, isNot(contains('<a')));
      expect(html, contains('[[   ]]'));
    });
  });

  group('WikiLinkSyntax.titleFromHref', () {
    test('round-trips titles with reserved characters', () {
      const title = 'Q&A: 50% / "draft" #2';
      final href = '${WikiLinkSyntax.scheme}:${Uri.encodeComponent(title)}';
      expect(WikiLinkSyntax.titleFromHref(href), title);
    });

    test('returns null for web links and null hrefs', () {
      expect(WikiLinkSyntax.titleFromHref('https://example.org'), isNull);
      expect(WikiLinkSyntax.titleFromHref(null), isNull);
    });
  });
}
