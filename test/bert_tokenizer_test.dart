import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/embedding/bert_tokenizer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late BertTokenizer tokenizer;

  setUpAll(() async {
    // Permet à rootBundle de charger l'asset depuis le projet en test.
    tokenizer = await BertTokenizer.loadFromAsset(
      'assets/models/tokenizer.json',
    );
  });

  test('vocab non vide et tokens spéciaux mappés', () {
    expect(tokenizer.vocab, isNotEmpty);
    expect(tokenizer.clsToken, greaterThan(0));
    expect(tokenizer.sepToken, greaterThan(0));
  });

  test('encode produit [CLS] ... [SEP] et taille bornée', () {
    final encoded = tokenizer.encode('hello world', maxLength: 16);
    expect(encoded.inputIds.first, tokenizer.clsToken);
    expect(encoded.inputIds.last, tokenizer.sepToken);
    expect(encoded.attentionMask.length, encoded.inputIds.length);
    expect(encoded.tokenTypeIds, everyElement(0));
  });

  test('respecte strictement maxLength', () {
    final long = List.filled(500, 'token').join(' ');
    final encoded = tokenizer.encode(long, maxLength: 32);
    expect(encoded.inputIds.length, lessThanOrEqualTo(32));
    expect(encoded.inputIds.last, tokenizer.sepToken);
  });

  test('insensibilité aux accents et casse', () {
    final a = tokenizer.encode('Été', maxLength: 16);
    final b = tokenizer.encode('ete', maxLength: 16);
    expect(a.inputIds, b.inputIds);
  });

  test('texte vide → seulement [CLS] [SEP]', () {
    final encoded = tokenizer.encode('', maxLength: 16);
    expect(encoded.inputIds, [tokenizer.clsToken, tokenizer.sepToken]);
  });

  test('caractère hors vocab → [UNK] mais pas de crash', () {
    final encoded = tokenizer.encode('🐉', maxLength: 16);
    expect(encoded.inputIds.first, tokenizer.clsToken);
    expect(encoded.inputIds.last, tokenizer.sepToken);
  });
}
