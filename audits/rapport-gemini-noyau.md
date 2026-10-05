Ce code est d'une excellente qualité. Les pièges classiques de SQLCipher, de Room et du Keystore sont anticipés, et la règle d'or (ne jamais écraser une clé si la base existe) est implémentée de façon robuste.

Voici les réponses à tes questions explicites, suivies de l'analyse des défauts selon tes critères.

### Réponses à tes 5 questions explicites

1. **Format de la clé brute (`SqlCipherRawKey.encode`) :** **Oui, c'est exact.** SQLCipher intercepte spécifiquement ce motif. S'il reçoit une clé dont la longueur est exactement `(taille_clé * 2) + 3`, qui commence par `x'` et finit par `'`, il décode l'hexadécimal et l'utilise comme clé brute, en contournant totalement la dérivation PBKDF2. Ton implémentation produit exactement les 67 octets attendus.
2. **`clearPassphrase = false` et durée de vie :** **Oui, c'est exact.** Si ce paramètre est à `true` (le défaut de la bibliothèque), SQLCipher remplit le tableau de zéros dès que la première connexion est établie. Comme Room utilise un pool, la deuxième connexion tentera d'utiliser un tableau vide et échouera. Passer `false` est obligatoire ici. La copie fournie par `rawKey.copyOf()` est conservée en interne par `SupportOpenHelperFactory` et **doit** survivre (et survivra) pendant toute la durée de vie du pool.
3. **`databaseExists()` comme seul discriminant :** **Oui, c'est suffisant et c'est le plus sûr.** C'est la seule preuve tangible qu'il y a des données à protéger.
   * *Cas où la base n'existe pas mais la clé Keystore existe :* (Ex: l'utilisateur a effacé les données de l'app, ce qui supprime la base et les SharedPreferences, mais la clé Keystore a survécu). Le code générera une nouvelle KEK, l'écrira dans les SharedPreferences en la chiffrant avec l'ancienne clé Keystore, puis créera une base neuve. C'est un comportement correct.
   * *Cas où la base existe mais est à 0 octet :* Le code refusera de générer une clé (sécurité maximale). Si une clé valide est trouvée, SQLCipher tentera d'ouvrir le fichier vide et échouera proprement.
4. **`INSERT OR IGNORE` sur `folders` sous WAL :** **Oui, c'est sûr.** SQLite optimise `INSERT OR IGNORE` : si la contrainte d'unicité (la clé primaire `inbox`) est violée, l'instruction se termine sans modifier la table. Par conséquent, **aucun trigger n'est déclenché** (donc la table de suivi d'invalidation de Room n'est pas touchée) et rien n'est écrit dans le journal WAL. C'est une opération blanche.
5. **Valeurs par défaut des entités :** **Aucune divergence.** Room compare la valeur déclarée avec la colonne `dflt_value` de `PRAGMA table_info`. Pour une chaîne, SQLite renvoie la valeur avec ses guillemets simples, donc `defaultValue = "''"` correspondra parfaitement à un `DEFAULT ''` en SQL. Pour les entiers/booléens, `"0"` et `"1"` correspondront parfaitement.

---

### 1. Perte de données (Gravité 1)

**Aucun défaut sur ce motif.**
La logique dans `KekRepository` est étanche. Les exceptions transitoires du Keystore (`GeneralSecurityException`) sont correctement transformées en `SourceUnavailable`, ce qui déclenche une nouvelle tentative puis un abandon propre, sans jamais renvoyer `null` (qui déclencherait une génération).

---

### 2. Défaut d'ouverture (Gravité 2)

**CONFIRMÉ — Erreur de compilation sur l'effacement mémoire**
*Fichier : `SecretBytes.kt`, ligne 39*
```kotlin
fun wipe(bytes: ByteArray) = Arrays.fill(bytes, 0)
```
* **Scénario :** Le code ne compilera pas. La méthode Java `java.util.Arrays.fill(byte[] a, byte val)` exige un type `byte`. En Kotlin, le littéral `0` est un `Int`. Le compilateur ne fera pas la conversion implicite lors de la résolution de la méthode Java et signalera un *Type mismatch*.
* **Résultat :** Échec de compilation.
* **Correction (pour info) :** Utiliser `Arrays.fill(bytes, 0.toByte())` ou la fonction d'extension native de Kotlin `bytes.fill(0)`.

**CONFIRMÉ — Blocage définitif sur une nouvelle installation après désinstallation**
*Fichier : `KeystoreSealedKekSource.kt`, ligne 83 (`store`)*
* **Scénario :**
  1. L'utilisateur installe l'application (une clé Keystore `notes_tech.db.kek.v1` est créée).
  2. Il désinstalle l'application. La base et les SharedPreferences sont supprimées, mais sur certains appareils/versions d'Android, la clé Keystore survit à la désinstallation.
  3. Il réinstalle l'application. `databaseExists()` est faux. `KekRepository` appelle `generateAndPersist()`.
  4. `store()` appelle `existingKey()`, qui trouve l'ancienne clé Keystore.
  5. L'OS a invalidé cette clé (fréquent après une désinstallation/réinstallation même sans `setUserAuthenticationRequired`). `Cipher.init(..., key)` lève une `KeyPermanentlyInvalidatedException`.
  6. `store()` l'attrape et jette `SourceUnavailable`. `generateAndPersist()` échoue.
* **Résultat :** L'application plante au démarrage et ne pourra **jamais** s'installer, car elle tentera indéfiniment de réutiliser une clé Keystore morte au lieu de l'écraser.
* **Note :** Ce n'est pas une perte de données (la base n'existe pas encore), mais un déni de service permanent pour cet utilisateur.

---

### 3. Fuite de secret (Gravité 3)

**Aucun défaut sur ce motif.**
* Les messages d'exception (`IllegalArgumentException`, `KekFailure`) sont tous statiques et ne concatènent jamais les variables fautives.
* La rétention en mémoire de `rawKey.copyOf()` par `SupportOpenHelperFactory` est inévitable pour maintenir le pool de connexions SQLCipher en vie. Ce n'est pas une fuite accidentelle, c'est le comportement requis par l'architecture.
* La création de `String` intermédiaires dans `SqlCipherRawKey` est documentée et assumée.

---

### 4. Concurrence (Gravité 4)

**Aucun défaut sur ce motif.**
* `loadNativeLibraryOnce()` utilise `@Synchronized` sur une méthode d'objet compagnon. Ce n'est pas un *double-checked locking* défectueux, c'est un verrouillage simple et monolithique de la méthode. C'est parfaitement *thread-safe*.
* `SecureRandom` et `SharedPreferences` sont *thread-safe* par nature.

---

### 5. Cohérence du schéma (Gravité 5)

**Aucun défaut sur ce motif.**
* Les triggers FTS5 (`notes_ai`, `notes_ad`, `notes_au`) utilisent la syntaxe exacte requise pour une table virtuelle à contenu externe (`content='notes'`).
* Les index déclarés dans `NoteEntity` et `FolderEntity` correspondent aux standards de Room et intègrent correctement les ordres de tri (`Index.Order.DESC`).
* Le choix de ne pas mapper `note_links` et d'utiliser `@RawQuery` est la seule solution viable pour passer la validation stricte de Room sans altérer le schéma hérité.