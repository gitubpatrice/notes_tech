/// Écran "À propos" — mise en avant de la promesse offline.
library;

import 'package:flutter/material.dart';

import '../../core/constants.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('À propos')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Row(
            children: [
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  color: theme.colorScheme.primary,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: const Icon(Icons.note_alt_outlined,
                    color: Colors.white, size: 32),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(AppConstants.appName,
                        style: theme.textTheme.titleLarge),
                    Text('Version ${AppConstants.appVersion}',
                        style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Text('Confidentialité', style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          const _Badge(
            icon: Icons.cloud_off_outlined,
            text: 'Aucune connexion réseau — vérifiable dans le manifeste',
          ),
          const _Badge(
            icon: Icons.account_circle_outlined,
            text: 'Aucun compte, aucune inscription',
          ),
          const _Badge(
            icon: Icons.bar_chart_outlined,
            text: 'Aucun tracker, aucune publicité',
          ),
          const _Badge(
            icon: Icons.lock_outline,
            text: 'Données stockées localement, jamais envoyées',
          ),
          const SizedBox(height: 24),
          Text('Auteur', style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          Text(AppConstants.appAuthor, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 4),
          Text('Licence Apache 2.0', style: theme.textTheme.bodySmall),
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 18, color: theme.iconTheme.color),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}
