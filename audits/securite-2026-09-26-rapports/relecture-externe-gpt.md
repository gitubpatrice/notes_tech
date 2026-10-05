## Findings

### 1. A protected-clipboard failure can turn **Cut** into permanent text loss

- **File/line:** `ui/secure/SaisieDeCoffre.kt`, lines 78–87
- **Severity:** **Medium**

#### Scenario

1. The user selects text in a vault note and chooses **Cut**.
2. Compose first calls `Clipboard.setClipEntry()`, then removes the selected text if that call returns normally.
3. `SensitiveClipboard.copier()` fails—for example because the platform clipboard is unavailable—and throws.
4. `PressePapiersDeCoffre` catches the exception and returns normally:
   ```kotlin
   try {
       deposer(texte)
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       Timber.w(e, "copie depuis une note de coffre refusee")
   }
   ```
5. Compose therefore considers the copy successful and completes the cut, deleting the text even though it was never placed on the clipboard. Autosave can then persist that deletion.

This is specifically worse for **Cut** than for Copy: suppressing the failure converts a clipboard failure into note-content loss.

#### Fix

Do not return success when the protected write failed. Propagate the exception so the text component does not proceed with the deletion:

```kotlin
override suspend fun setClipEntry(clipEntry: ClipEntry?) {
    val texte = clipEntry?.clipData
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()

    if (!actif() || texte == null) {
        natif.setClipEntry(clipEntry)
        return
    }

    deposer(texte) // Let failure propagate.
}
```

If Compose’s exact text-field implementation catches clipboard exceptions and still deletes the selection, then a custom Cut command/result protocol is required. I am reasonably confident the standard Compose cut path only deletes after the suspend clipboard call returns successfully, but this should be confirmed against the exact Compose 1.11 implementation used by the BOM.

---

### 2. Whisper cancellation still has a race where the stop request is erased

- **File/line:** `data/voice/WhisperStt.kt`, lines 147–157
- **Severity:** **Low**

#### Scenario

The child coroutine has started executing but has not yet entered `natif.transcrire()`:

```kotlin
val calcul = async(Dispatchers.Default) {
    natif.transcrire(poigneeCourante, echantillons, langue.orEmpty(), filsDeCalcul())
}
```

Cancellation then causes `await()` to throw, and the parent requests a stop:

```kotlin
} catch (e: CancellationException) {
    natif.demanderArret(poigneeCourante)
    throw e
}
```

The child can subsequently enter `transcrire()`. According to the patch’s own comment, `transcrire` clears the stop flag at its start:

> “`transcrire` clears the flag at its start”

That erases the cancellation request, so the native transcription may run to completion. `coroutineScope` then waits for it, reproducing the long cancellation delay this change is intended to remove.

This requires a narrow ordering: the child must have begun executing far enough that coroutine cancellation no longer prevents its body from running, but not yet called the native method.

#### Fix

Make native startup and cancellation ordering explicit. For example:

- have the native side use a per-transcription generation/token and never clear a cancellation belonging to the current generation; or
- publish “native call initialized and stop flag reset” before the parent is allowed to issue the stop request; or
- provide one native entry point that atomically resets the flag, marks the operation started, and then honors any cancellation registered for that operation.

A plain pre-call `ensureActive()` narrows the race but does not eliminate cancellation between that check and native entry.

---

### 3. Failure to remove the panic journal creates a repeat-at-startup dead end

- **File/line:**  
  - `security/panic/PanicService.kt`, lines 470–474  
  - `ui/startup/StartupViewModel.kt`, lines 82–87
- **Severity:** **Medium**

#### Scenario

1. `journal.markStarted()` successfully persists `panic_in_progress`.
2. The panic sequence reaches its end.
3. Removing the journal fails, for example because the preferences file or filesystem has developed a persistent write error:
   ```kotlin
   if (!journal.clear()) Timber.w("panique : journal de reprise non retire")
   ```
4. On every subsequent launch, startup sees the stale flag:
   ```kotlin
   if (panicJournal.isPending()) {
       _state.value = StartupState.ResumingPanic
       return
   }
   ```
5. The app runs the panic again, reaches the same failed `clear()`, and never reaches `StartupState.Ready`.

There is no user-visible escape or retry path that can bypass a demonstrably completed panic. A persistent preference-write failure therefore creates a startup loop, even after all data has already been destroyed.

#### Fix

Distinguish an unfinished panic from a completed panic whose marker could not be removed. In particular, startup can safely treat the marker as stale after verifying the panic’s terminal destruction conditions—for example, the database and all database-key sources are absent—and then avoid rerunning the full sequence.

Alternatively, use a journal with explicit phases and recovery validation, but merely writing a second “completed” preference has the same failure mode if all preference commits are failing. The recovery decision needs to be based on the actual destruction state, not solely on another writable marker.

---

I did not find a verified SQL/FTS5 correctness defect in the shown changes: the FTS5 `secure-delete` command, subsequent `optimize`, external-content delete-trigger model, and row-returning pragma handling are consistent with the documented FTS5/SQLite behavior, assuming the bundled SQLCipher SQLite is version 3.42 or newer as required by the patch.