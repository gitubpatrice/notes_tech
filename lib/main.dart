/// Point d'entrée — initialisation parallèle puis injection de dépendances.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:provider/provider.dart';
import 'package:sqflite/sqflite.dart' show Database;

import 'app.dart';
import 'data/db/database.dart';
import 'data/db/folders_dao.dart';
import 'data/db/notes_dao.dart';
import 'data/repositories/folders_repository.dart';
import 'data/repositories/notes_repository.dart';
import 'services/settings_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Initialisations indépendantes lancées en parallèle.
  final dateInit = initializeDateFormatting('fr_FR');
  final settingsInit = SettingsService.create();
  final dbInit = AppDatabase.instance.db;
  await dateInit;
  final settings = await settingsInit;
  final Database db = await dbInit;

  final notesRepo = NotesRepository(NotesDao(db));
  final foldersRepo = FoldersRepository(FoldersDao(db));

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
        Provider<NotesRepository>.value(value: notesRepo),
        Provider<FoldersRepository>.value(value: foldersRepo),
      ],
      child: const NotesTechApp(),
    ),
  );
}
