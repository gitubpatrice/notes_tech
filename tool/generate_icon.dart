/// Génère l'icône Notes Tech.
///
/// - Fond divisé en 4 quadrants diagonaux (rouge / bleu).
/// - "NOTES" et "TECH" empilés verticalement, en blanc, lettres
///   construites à partir de segments rectangulaires (style typographique
///   épuré, lisible même à petite taille).
/// - Coins repris en couleur du quadrant pour un masque adaptatif Android propre.
///
/// Lancement : `dart run tool/generate_icon.dart`
/// Génère deux fichiers :
///   - `assets/icon/app_icon.png` (icône principale)
///   - `assets/icon/app_icon_fg.png` (foreground pour l'adaptive icon Android)
library;

import 'dart:io';

import 'package:image/image.dart' as img;

const int _size = 1024;
const int _half = _size ~/ 2;
const int _radius = 180;

// Palette
final _blue = img.ColorRgb8(31, 111, 235); // GitHub blue (cohérence app)
final _red = img.ColorRgb8(218, 54, 51); // GitHub red
final _white = img.ColorRgb8(255, 255, 255);
final _transparent = img.ColorRgba8(0, 0, 0, 0);

void main() {
  final main = _buildIcon(transparent: false);
  final fg = _buildIcon(transparent: true);

  Directory('assets/icon').createSync(recursive: true);
  File('assets/icon/app_icon.png').writeAsBytesSync(img.encodePng(main));
  File('assets/icon/app_icon_fg.png').writeAsBytesSync(img.encodePng(fg));
  // ignore: avoid_print
  print('✓ assets/icon/app_icon.png + app_icon_fg.png générés');
}

img.Image _buildIcon({required bool transparent}) {
  final image = img.Image(width: _size, height: _size, numChannels: 4);
  if (transparent) {
    img.fill(image, color: _transparent);
  } else {
    // 4 quadrants diagonaux : haut-gauche bleu, haut-droite rouge,
    // bas-gauche rouge, bas-droite bleu.
    img.fillRect(image, x1: 0, y1: 0, x2: _half, y2: _half, color: _blue);
    img.fillRect(image, x1: _half, y1: 0, x2: _size, y2: _half, color: _red);
    img.fillRect(image, x1: 0, y1: _half, x2: _half, y2: _size, color: _red);
    img.fillRect(
        image, x1: _half, y1: _half, x2: _size, y2: _size, color: _blue);
    _roundCorners(image);
  }

  // Texte vertical "NOTES TECH" centré.
  _drawVerticalText(image);
  return image;
}

void _roundCorners(img.Image image) {
  final corners = [
    (cx: _radius, cy: _radius, x0: 0, y0: 0, isTopLeft: true),
    (cx: _size - _radius, cy: _radius, x0: _size - _radius, y0: 0, isTopLeft: false),
    (cx: _radius, cy: _size - _radius, x0: 0, y0: _size - _radius, isTopLeft: false),
    (
      cx: _size - _radius,
      cy: _size - _radius,
      x0: _size - _radius,
      y0: _size - _radius,
      isTopLeft: true,
    ),
  ];
  for (final c in corners) {
    for (var dy = 0; dy < _radius; dy++) {
      for (var dx = 0; dx < _radius; dx++) {
        final px = c.x0 + dx;
        final py = c.y0 + dy;
        final ddx = px - c.cx;
        final ddy = py - c.cy;
        if (ddx * ddx + ddy * ddy > _radius * _radius) {
          image.setPixel(px, py, _transparent);
        }
      }
    }
  }
}

/// Dessine "NOTES" puis "TECH" empilés verticalement.
/// Chaque lettre est rendue verticalement à l'endroit (pas couchée),
/// dans une cellule plus haute que large pour respecter les proportions
/// typographiques classiques.
void _drawVerticalText(img.Image image) {
  // 9 lettres × 96 px + gap 40 entre NOTES et TECH = 904 + marges.
  const cellH = 96;
  const cellW = 140;
  const stroke = 18;
  const gap = 40;
  const totalH = 5 * cellH + gap + 4 * cellH;
  final yStart = (_size - totalH) ~/ 2;
  final xLeft = (_size - cellW) ~/ 2;

  var y = yStart;
  for (final c in 'NOTES'.split('')) {
    _drawLetter(image, c, xLeft, y, cellW, cellH, stroke);
    y += cellH;
  }
  y += gap;
  for (final c in 'TECH'.split('')) {
    _drawLetter(image, c, xLeft, y, cellW, cellH, stroke);
    y += cellH;
  }
}

/// Dessine une lettre dans la cellule (x, y, w, h) avec une épaisseur `s`.
void _drawLetter(
  img.Image image,
  String letter,
  int x,
  int y,
  int w,
  int h,
  int s,
) {
  // Marge interne pour aérer la lettre dans sa cellule.
  const padY = 8;
  final top = y + padY;
  final bottom = y + h - padY;
  final left = x;
  final right = x + w;
  final mid = (top + bottom) ~/ 2;

  void rect(int x1, int y1, int x2, int y2) =>
      img.fillRect(image, x1: x1, y1: y1, x2: x2, y2: y2, color: _white);

  switch (letter) {
    case 'N':
      // Deux montants verticaux + diagonale.
      rect(left, top, left + s, bottom);
      rect(right - s, top, right, bottom);
      _drawDiagonal(image, left + s, top, right - s, bottom, s);
    case 'O':
      // Cadre avec intérieur évidé : deux rects + 2 horizontaux.
      rect(left, top, right, top + s); // haut
      rect(left, bottom - s, right, bottom); // bas
      rect(left, top, left + s, bottom); // gauche
      rect(right - s, top, right, bottom); // droite
    case 'T':
      rect(left, top, right, top + s); // barre haute
      rect(left + (w ~/ 2) - (s ~/ 2), top, left + (w ~/ 2) + (s ~/ 2),
          bottom); // tige
    case 'E':
      rect(left, top, right, top + s); // haut
      rect(left, mid - (s ~/ 2), right - (w ~/ 6), mid + (s ~/ 2)); // milieu
      rect(left, bottom - s, right, bottom); // bas
      rect(left, top, left + s, bottom); // gauche
    case 'S':
      rect(left, top, right, top + s); // haut
      rect(left, top, left + s, mid + (s ~/ 2)); // gauche-haut
      rect(left, mid - (s ~/ 2), right, mid + (s ~/ 2)); // milieu
      rect(right - s, mid - (s ~/ 2), right, bottom); // droite-bas
      rect(left, bottom - s, right, bottom); // bas
    case 'C':
      rect(left, top, right, top + s);
      rect(left, top, left + s, bottom);
      rect(left, bottom - s, right, bottom);
    case 'H':
      rect(left, top, left + s, bottom);
      rect(right - s, top, right, bottom);
      rect(left, mid - (s ~/ 2), right, mid + (s ~/ 2));
  }
}

/// Diagonale épaissie (Bresenham simple, multi-pixel pour épaisseur).
void _drawDiagonal(
  img.Image image,
  int x1,
  int y1,
  int x2,
  int y2,
  int thickness,
) {
  final dx = x2 - x1;
  final dy = y2 - y1;
  final steps = dx.abs() > dy.abs() ? dx.abs() : dy.abs();
  if (steps == 0) return;
  for (var i = 0; i <= steps; i++) {
    final t = i / steps;
    final px = (x1 + dx * t).round();
    final py = (y1 + dy * t).round();
    img.fillCircle(image, x: px, y: py, radius: thickness ~/ 2, color: _white);
  }
}
