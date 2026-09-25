# Política de privacidad — Notes Tech

**Versión 1.2.0 — septiembre de 2026**

## En una frase

Notes Tech no recopila, no transmite ni almacena ningún dato en servidores remotos. Todo se queda en su teléfono, y la base de datos está cifrada en reposo.

## En detalle

### Datos tratados

- **Sus notas en Markdown**: creadas y conservadas exclusivamente en su teléfono, en una base de datos SQLite cifrada con **SQLCipher** mediante una clave única generada localmente (KEK de 32 bytes) guardada en el **Android Keystore**.
- **Cajas fuertes por carpeta**: cada caja fuerte que active usa una **frase de contraseña** o un **PIN** propios, derivados mediante **Argon2id RFC 9106** (m=64MB, t=3 para la frase de contraseña; más ligero para el PIN, compensado por un sellado en el Keystore ligado al dispositivo). El contenido de las notas bloqueadas se cifra con **AES-256-GCM**, con AAD vinculado a `note_id`.
- **Bloqueo de la aplicación (opcional)**: el PIN nunca se guarda. Se conserva un valor derivado de él con **Argon2id** y vinculado mediante **HMAC-SHA256** a una clave del **Android Keystore** que nunca sale del teléfono, de modo que no puede comprobarse en ningún otro lugar. Tras cinco PIN incorrectos, cada nuevo intento espera más (30 segundos, que se duplican hasta una hora); los intentos fallidos nunca borran nada. El **desbloqueo con huella dactilar o rostro** lo gestiona íntegramente Android: Notes Tech no recibe ningún dato biométrico, solo la confirmación de que se ha permitido usar una clave del Keystore, y solo se acepta la biometría fuerte (clase 3).
- **Retroenlaces `[[Título]]`**: índice invertido local, nunca transmitido.
- **Modelo de dictado por voz (Whisper `.bin`)**: lo obtiene usted mismo —la aplicación muestra el nombre del archivo y su origen— y luego lo importa mediante el selector de documentos de Android. Notes Tech no tiene permiso de acceso a Internet y **no ofrece ninguna forma de descargar nada**. Su SHA-256 se comprueba al importarlo y antes de cada carga.
- **Audio captado durante el dictado**: se escribe en un archivo temporal del almacenamiento privado de la aplicación —el motor de transcripción lee un archivo, no hay otra manera— y **se borra en cuanto llega la transcripción**. También se borra al iniciar la aplicación y con el modo pánico, de modo que un cierre brusco no deja nada.
- **Ajustes (tema, orden, dictado activado, bloqueo automático de las cajas fuertes, opciones del bloqueo de la aplicación)**: guardados sin cifrar en las preferencias locales (ningún dato sensible).

### Datos que NO se tratan

- **Ninguna telemetría**, ninguna analítica, ningún informe de errores a terceros.
- **Ninguna publicidad**, ningún rastreador.
- **Ninguna cuenta de usuario**, ninguna conexión a un servicio en línea.

### Permisos de Android solicitados

Notes Tech **NO solicita el permiso `INTERNET`**. La aplicación es técnicamente incapaz de comunicarse con un servidor remoto. Esta ausencia puede comprobarse en el `AndroidManifest.xml` del repositorio de código fuente (`tools:node="remove"` en INTERNET y ACCESS_NETWORK_STATE).

Los permisos activos son estrictamente funcionales:
- `RECORD_AUDIO` (dictado: el audio se escribe en un archivo temporal privado y se borra en cuanto llega la transcripción).
- `USE_BIOMETRIC` y `USE_FINGERPRINT` (desbloqueo opcional del bloqueo de la aplicación con huella dactilar o rostro; declarados por la biblioteca biométrica de AndroidX —`USE_FINGERPRINT` es el nombre que tiene el mismo permiso en Android 8 y versiones anteriores).

Estos son los **únicos** permisos solicitados. La selección del archivo del modelo pasa por el selector de documentos de Android, que no requiere ninguno.

### Modo pánico

El menú **Ajustes → Modo pánico** borra de una vez:
- la base de datos SQLite cifrada (todas las notas),
- la KEK de SQLCipher (irrecuperable),
- las claves del Keystore asociadas a las cajas fuertes con PIN,
- las claves del Keystore del bloqueo de la aplicación (comprobación del PIN y desbloqueo biométrico),
- las cajas fuertes por carpeta (frases de contraseña y PIN),
- el portapapeles, donde una nota copiada está sin cifrar,
- los archivos de exportación y las grabaciones de dictado, los únicos archivos sin cifrar de la aplicación,
- el modelo Whisper instalado en el espacio aislado,
- las preferencias (excepto `db_encrypted_v1` y `secure_window_enabled`, que se conservan para que el reinicio sea coherente).

El borrado **no es atómico**, y el orden de los pasos está pensado en función de ello: la clave de cifrado se destruye **antes** de los borrados largos, después los archivos sin cifrar y después el resto. Un cierre brusco en cualquier instante deja por tanto el estado más seguro alcanzable en ese instante: en el peor de los casos, una base de datos reducida a ruido. La pantalla final indica qué ha fallado y **si puede quedar algo legible**.

### Sus derechos

Como todos los datos son estrictamente locales, el RGPD se aplica entre usted y su teléfono. En cualquier momento puede:
- exportar sus notas en Markdown o ZIP (`Ajustes → Exportar todas mis notas`),
- borrar todos los datos mediante el modo pánico,
- desinstalar la aplicación: Android borrará automáticamente todos los datos privados.

### Encargados del tratamiento

**Ninguno.** Notes Tech no utiliza ningún servicio de terceros durante su ejecución.

### Modelo de dictado por voz

- **Whisper** (modelos `.bin` de `ggerganov/whisper.cpp`): licencia MIT.

El archivo que cargue se queda en su teléfono. Notes Tech se limita a ejecutarlo localmente con el motor `whisper.cpp` **integrado en la aplicación**, cuya licencia MIT se reproduce en las condiciones de uso.

### Idioma

Esta política se redactó en francés; las versiones en otros idiomas son traducciones. En caso de discrepancia, **prevalece la versión francesa**.

### Contacto

Para cualquier pregunta: **contact@files-tech.com**

---

Notes Tech es una publicación de **Patrice Haltaya**. Código fuente publicado bajo la licencia **Apache 2.0**.
