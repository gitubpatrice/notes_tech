/// Every install before 2.0.9 seeded the inbox as "Boîte de réception",
/// whatever the device language: an English user saw a French folder name in
/// the app and in exports (reported on fdroiddata!37885).
library;

import 'dart:ui' show Locale;

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/core/constants.dart';
import 'package:notes_tech/data/models/folder.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/l10n/app_localizations.dart';
import 'package:notes_tech/services/export/note_export_service.dart';
import 'package:notes_tech/utils/folder_localize.dart';

void main() {
  final epoch = DateTime.fromMillisecondsSinceEpoch(0);
  Folder folder(String id, String name) =>
      Folder(id: id, name: name, createdAt: epoch, updatedAt: epoch);
  Folder inbox(String name) => folder(AppConstants.inboxFolderId, name);
  final note = Note(
    id: 'n1',
    title: 'Groceries',
    content: 'Milk',
    folderId: AppConstants.inboxFolderId,
    createdAt: epoch,
    updatedAt: epoch,
  );
  final en = lookupAppLocalizations(const Locale('en'));
  final fr = lookupAppLocalizations(const Locale('fr'));

  group('folder display name', () {
    test('a default inbox name follows the active language', () {
      for (final seeded in [
        'Boîte de réception',
        AppConstants.inboxDefaultName,
      ]) {
        expect(inbox(seeded).displayName(en), 'Inbox');
        expect(inbox(seeded).displayName(fr), 'Boîte de réception');
      }
    });

    test('a name the user chose is kept, and only the inbox is concerned', () {
      expect(inbox('Personal').displayName(fr), 'Personal');
      expect(
        folder('f1', 'Boîte de réception').displayName(en),
        'Boîte de réception',
      );
    });
  });

  group('export', () {
    const service = NoteExportService();

    List<String> zipDirs(Folder f) => ZipDecoder()
        .decodeBytes(
          service.exportAsZip(
            notes: [note],
            foldersById: {f.id: f},
            inboxFallbackName: 'Inbox',
          ),
        )
        .files
        .map((e) => e.name)
        .where((name) => name.endsWith('.md') && name.contains('/'))
        .map((name) => name.substring(0, name.indexOf('/')))
        .toList();

    test('a default inbox exports under the technical directory name', () {
      expect(zipDirs(inbox('Boîte de réception')), ['inbox']);
      expect(zipDirs(inbox('Personal')), ['Personal']);
    });

    test('a default inbox gets the fallback name in the frontmatter', () {
      final md = service.renderNoteAsMarkdown(
        note,
        folder: inbox('Boîte de réception'),
        inboxFallbackName: 'Inbox',
      );
      expect(md, contains('folder: "Inbox"'));
      expect(
        service.renderNoteAsMarkdown(note, folder: inbox('Personal')),
        contains('folder: "Personal"'),
      );
    });
  });
}
