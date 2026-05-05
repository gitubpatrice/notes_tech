/// Petit utilitaire de debounce pour les appels rapides (recherche, autosave).
library;

import 'dart:async';

class Debouncer {
  Debouncer(this.delay);
  final Duration delay;
  Timer? _timer;

  void run(void Function() action) {
    _timer?.cancel();
    _timer = Timer(delay, action);
  }

  /// Exécute l'action immédiatement et annule tout timer en cours.
  void flush(void Function() action) {
    _timer?.cancel();
    _timer = null;
    action();
  }

  void cancel() {
    _timer?.cancel();
    _timer = null;
  }

  bool get isActive => _timer?.isActive ?? false;

  void dispose() => cancel();
}
