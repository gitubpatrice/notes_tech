import 'package:flutter/material.dart';
import '../../l10n/app_localizations.dart';
import '../../services/first_launch_flag.dart';

/// v1.1.1 — Splash de présentation Files Tech joué **uniquement au premier
/// lancement** après install (ou après "Effacer données"). 100% Flutter natif,
/// zéro dépendance ajoutée — cohérent avec l'esprit sobre des apps Files Tech.
///
/// Mécanique identique sur les 6 apps Files Tech (SMS Tech Kotlin référence
/// + 5 ports Flutter) pour cimenter la cohérence de marque :
///   - logo : `scale 0.5 → 1.0` + `opacity 0 → 1` sur 900 ms, easeOutCubic
///   - titre + tagline : `opacity 0 → 1` sur 800 ms après délai de 700 ms
///   - hint "Toucher l'écran pour continuer" : fade in à 2500 ms, opacité 0.6
///   - auto-dismiss à 5500 ms
///
/// **Interactions** (toutes idempotentes via `_dismissOnce`) :
///   - tap n'importe où → skip immédiat
///   - back hardware → skip immédiat
///   - auto-dismiss à 5500 ms
///
/// **Sécurité** : ce splash ne touche jamais au vault, ne lit aucune donnée
/// sensible, et hérite du FLAG_SECURE déjà appliqué côté natif
/// (MainActivity.onCreate). Il ne bypasse pas les coffres par dossier.
class SplashScreen extends StatefulWidget {
  final VoidCallback onFinished;

  const SplashScreen({super.key, required this.onFinished});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with TickerProviderStateMixin {
  late final AnimationController _logoCtrl;
  late final AnimationController _taglineCtrl;
  late final AnimationController _hintCtrl;
  late final Animation<double> _logoScale;
  late final Animation<double> _logoAlpha;
  late final Animation<double> _taglineAlpha;
  late final Animation<double> _hintAlpha;

  /// Garde d'idempotence partagée entre toutes les portes de sortie (tap, back,
  /// auto-dismiss). Empêche un double-`onFinished` côté Navigator.
  bool _fired = false;

  @override
  void initState() {
    super.initState();

    _logoCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 900),
    );
    _taglineCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _hintCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );

    _logoScale = Tween<double>(
      begin: 0.5,
      end: 1.0,
    ).chain(CurveTween(curve: Curves.easeOutCubic)).animate(_logoCtrl);
    _logoAlpha = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).chain(CurveTween(curve: Curves.easeOutCubic)).animate(_logoCtrl);
    _taglineAlpha = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).chain(CurveTween(curve: Curves.easeOutCubic)).animate(_taglineCtrl);
    _hintAlpha = Tween<double>(begin: 0.0, end: 0.6).animate(_hintCtrl);

    _logoCtrl.forward();
    Future.delayed(const Duration(milliseconds: 700), () {
      if (mounted) _taglineCtrl.forward();
    });
    Future.delayed(const Duration(milliseconds: 2500), () {
      if (mounted) _hintCtrl.forward();
    });
    Future.delayed(const Duration(milliseconds: 5500), () {
      if (mounted) _dismissOnce();
    });
  }

  void _dismissOnce() {
    if (_fired) return;
    _fired = true;
    // Fire-and-forget : la persistance prefs n'a pas besoin d'être awaitée
    // avant la navigation (idempotente). En cas de crash entre navigation et
    // write, on rejoue le splash au prochain boot — comportement acceptable.
    FirstLaunchFlag.markShown();
    widget.onFinished();
  }

  @override
  void dispose() {
    _logoCtrl.dispose();
    _taglineCtrl.dispose();
    _hintCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final size = MediaQuery.of(context).size;
    final logoSize = (size.width * 0.4).clamp(128.0, 200.0);
    final theme = Theme.of(context);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (_, _) => _dismissOnce(),
      child: Semantics(
        container: true,
        label: t.splashSemanticsLabel,
        hint: t.splashSkipHint,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _dismissOnce,
          child: Scaffold(
            backgroundColor: theme.colorScheme.surface,
            body: Stack(
              children: [
                Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      AnimatedBuilder(
                        animation: _logoCtrl,
                        builder: (context, _) => Opacity(
                          opacity: _logoAlpha.value,
                          child: Transform.scale(
                            scale: _logoScale.value,
                            child: Semantics(
                              image: true,
                              label: t.splashLogoContentDescription,
                              child: Image.asset(
                                'assets/icon/app_icon.png',
                                width: logoSize,
                                height: logoSize,
                                cacheWidth: 400,
                                filterQuality: FilterQuality.medium,
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),
                      AnimatedBuilder(
                        animation: _taglineCtrl,
                        builder: (context, _) => Opacity(
                          opacity: _taglineAlpha.value,
                          child: Column(
                            children: [
                              Text(
                                t.appTitle,
                                style: theme.textTheme.headlineMedium?.copyWith(
                                  fontWeight: FontWeight.w600,
                                  color: theme.colorScheme.onSurface,
                                ),
                              ),
                              const SizedBox(height: 12),
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 32,
                                ),
                                child: Text(
                                  t.splashTagline,
                                  textAlign: TextAlign.center,
                                  style: theme.textTheme.bodyLarge?.copyWith(
                                    color: theme.colorScheme.onSurfaceVariant,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 48,
                  child: ExcludeSemantics(
                    child: AnimatedBuilder(
                      animation: _hintCtrl,
                      builder: (context, _) => Opacity(
                        opacity: _hintAlpha.value,
                        child: Text(
                          t.splashSkipHint,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodySmall?.copyWith(
                            fontSize: 13,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// v1.1.1 — Widget racine qui choisit splash vs app au premier frame.
///
/// Évite `Navigator.pushReplacement` (qui dépend d'un context Navigator déjà
/// monté) : on swap simplement le `home` racine via setState. Pas d'historique
/// laissé dans le back stack, pas de transition coûteuse.
///
/// `nextChild` = le widget normalement affiché en `home` (HomeScreen pour
/// Notes Tech). Le splash s'affiche d'abord puis cède la place à `nextChild`
/// une fois dismissé.
class SplashGate extends StatefulWidget {
  final bool showSplash;
  final Widget nextChild;

  const SplashGate({
    super.key,
    required this.showSplash,
    required this.nextChild,
  });

  @override
  State<SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<SplashGate> {
  late bool _showSplash;

  @override
  void initState() {
    super.initState();
    _showSplash = widget.showSplash;
  }

  @override
  Widget build(BuildContext context) {
    if (!_showSplash) return widget.nextChild;
    return SplashScreen(
      onFinished: () {
        if (!mounted) return;
        setState(() => _showSplash = false);
      },
    );
  }
}
