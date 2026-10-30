# Política de privacidad — Notes Tech

_Última actualización: 30 de octubre de 2026_

## En una frase

Notes Tech no recopila, no transmite ni almacena ningún dato en servidores remotos. Todo se queda en su teléfono, y la base de datos está cifrada en reposo.

## En detalle

### Datos tratados

- **Sus notas en Markdown**: creadas y conservadas exclusivamente en su teléfono, en una base de datos SQLite cifrada con **SQLCipher** mediante una clave única generada localmente (KEK de 32 bytes) guardada en el **Android Keystore**.
- **Cajas fuertes por carpeta**: cada caja fuerte que active usa una **frase de contraseña** o un **PIN** propios, derivados mediante **Argon2id RFC 9106** (m=64MB, t=3 para la frase de contraseña; más ligero para el PIN, compensado por un sellado en el Keystore ligado al dispositivo). El contenido de las notas bloqueadas se cifra con **AES-256-GCM**, con AAD vinculado a `note_id`. Antes de Android 9, Android no puede vincular este sellado al desbloqueo del teléfono: la aplicación solo ofrece entonces una frase de contraseña para una nueva caja fuerte, y una caja fuerte con PIN creada antes ya no resiste, en un teléfono incautado, una búsqueda de su código.
- **Etiquetas y color de una nota de caja fuerte**: permanecen en la base de datos cifrada por SQLCipher, pero **fuera del cifrado propio de la caja fuerte**: solo el título y el contenido quedan bajo él. En las listas, una nota de caja fuerte nunca muestra sus etiquetas, y muestra su color solo mientras esa caja fuerte está desbloqueada.
- **Bloqueo de la aplicación (opcional)**: el PIN nunca se guarda. Se conserva un valor derivado de él con **Argon2id** y vinculado mediante **HMAC-SHA256** a una clave del **Android Keystore** que nunca sale del teléfono, de modo que no puede comprobarse en ningún otro lugar. Tras cinco PIN incorrectos, cada nuevo intento espera más (30 segundos, que se duplican hasta una hora); los intentos fallidos nunca borran nada. El **desbloqueo con huella dactilar o rostro** lo gestiona íntegramente Android: Notes Tech no recibe ningún dato biométrico, solo la confirmación de que se ha permitido usar una clave del Keystore, y solo se acepta la biometría fuerte (clase 3).
- **Retroenlaces `[[Título]]`**: índice invertido local, nunca transmitido.
- **Copia de seguridad cifrada (opcional)**: un archivo `.ntbackup` que usted mismo crea, en el lugar que elige mediante el selector de documentos de Android. Contiene sus carpetas y sus notas, papelera incluida, cifradas con **AES-256-GCM** con una clave derivada mediante **Argon2id** (m=64MB, t=3) de un código que genera la aplicación y que usted anota, o de una frase de contraseña propia; Notes Tech no guarda ni uno ni otra. Primero se escribe, ya cifrado, en el almacenamiento privado de la aplicación; luego se copia al lugar elegido y se borra de ese almacenamiento. Las notas de una caja fuerte con frase de contraseña siguen selladas por su frase en el archivo. Una caja fuerte con PIN solo viaja abierta, con su clave: en el archivo solo la protege el secreto de la copia; cerrada, queda fuera. La restauración añade lo que falta y no sobrescribe nada.
- **Notas importadas y compartidas**: los archivos Markdown o de texto —o un archivo ZIP que los contenga— que usted elige en el selector de documentos de Android, y los textos o archivos que comparte con Notes Tech desde otra app, se convierten en notas normales en sus carpetas, nunca en una caja fuerte. Se leen una sola vez, en el teléfono; los originales no se modifican y no se conserva nada aparte de las notas creadas. Como máximo 500.000 caracteres por nota.
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

Estos son los **únicos** permisos solicitados. La selección de un archivo —el modelo de dictado, notas que importar, una copia de seguridad— pasa por el selector de documentos de Android, que no requiere ninguno.

### Modo pánico

El menú **Ajustes → Modo pánico** borra de una vez:
- la base de datos SQLite cifrada (todas las notas),
- la KEK de SQLCipher (irrecuperable),
- las claves del Keystore asociadas a las cajas fuertes con PIN,
- las claves del Keystore del bloqueo de la aplicación (comprobación del PIN y desbloqueo biométrico),
- las cajas fuertes por carpeta (frases de contraseña y PIN),
- el portapapeles, donde una nota copiada está sin cifrar — pero no el historial que un teclado pueda guardar de ella, fuera del alcance de cualquier aplicación,
- los archivos de exportación y las grabaciones de dictado, los únicos archivos sin cifrar de la aplicación,
- el modelo Whisper instalado en el espacio aislado,
- las preferencias (excepto `db_encrypted_v1` y `secure_window_enabled`, que se conservan para que el reinicio sea coherente).

El modo pánico no alcanza una copia de seguridad cifrada guardada fuera de la aplicación, dondequiera que la haya puesto: bórrela usted mismo.

El borrado **no es atómico**, y el orden de los pasos está pensado en función de ello: la clave de cifrado se destruye **antes** de los borrados largos, después los archivos sin cifrar y después el resto. Un cierre brusco en cualquier instante deja por tanto el estado más seguro alcanzable en ese instante: en el peor de los casos, una base de datos reducida a ruido. La pantalla final indica qué ha fallado y **si puede quedar algo legible**.

### Sus derechos

Sus datos permanecen en su teléfono, bajo su exclusivo control, y el editor no tiene ningún medio de acceder a ellos: por tanto, no trata ningún dato personal suyo. Es el caso que la CNIL, la autoridad francesa de protección de datos, describe como un «simple software puesto a disposición del usuario», al que no se aplica el RGPD (recomendación sobre las aplicaciones móviles, apartado 3.3). Usted conserva el control de sus datos y en cualquier momento puede:
- exportar sus notas en Markdown o ZIP (`Ajustes → Exportar todas mis notas`),
- hacer una copia de seguridad cifrada de ellas y restaurarla (`Ajustes → Copia de seguridad cifrada`),
- eliminar una nota definitivamente, desde la papelera o con una pulsación larga en la lista de notas,
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

El editor no recibe ningún dato de la aplicación. Solo le llega un correo que usted decida escribirle: solo sirve para responderle, se conserva el tiempo del intercambio y después se borra. Puede solicitar el acceso a él o su supresión en la misma dirección, y reclamar ante la CNIL, la autoridad francesa de protección de datos (www.cnil.fr), si considera que no se respetan sus derechos.

---

Notes Tech es una publicación de **Patrice Haltaya**. Código fuente publicado bajo la licencia **Apache 2.0**.
