# Condiciones de uso — Notes Tech

**Versión 1.1.0 — septiembre de 2026**

## Licencia

Notes Tech es software libre publicado bajo la **licencia Apache 2.0**. Puede usarlo, modificarlo y redistribuirlo en los términos de esa licencia. El texto completo está disponible en el archivo `LICENSE` del repositorio de código fuente (https://github.com/gitubpatrice/notes_tech).

## Uso

Un particular pone la aplicación a disposición **de forma gratuita**, a título personal y sin fines comerciales, y se ofrece **tal cual, sin garantía de ningún tipo** (licencia Apache 2.0, sección 7). El uso que haga de ella es **responsabilidad exclusiva suya**, al igual que el contenido de sus notas. El dictado por voz se basa en un modelo de reconocimiento automático del habla, que puede producir transcripciones imperfectas.

## Limitaciones

- Notes Tech **no sustituye en ningún caso** un asesoramiento médico, jurídico, financiero o profesional.
- Whisper puede transcribir de forma incorrecta, sobre todo en entornos ruidosos o con términos técnicos especializados.
- El rendimiento depende de su hardware y del modelo cargado.

## Modo pánico y pérdida de datos

El **modo pánico** borra de forma definitiva e irreversible sus notas, su clave de cifrado y sus modelos. **No es posible ninguna recuperación**: es intencionado. Antes de usarlo, exporte lo que quiera conservar mediante `Ajustes → Exportar todas mis notas`.

Del mismo modo, **olvidar la frase de contraseña de una caja fuerte hace que sus notas sean ilegibles para siempre**: la frase de contraseña nunca se guarda, solo sirve para derivar la clave mediante Argon2id. No existe ningún procedimiento de recuperación.

En las cajas fuertes con **PIN**, **5 errores seguidos provocan un borrado automático** (eliminación de la clave del Keystore). Es el mismo comportamiento que el del bloqueo de pantalla habitual de Android, pero solo desde Android 9: antes, Android no puede vincular esta clave al desbloqueo del teléfono, la aplicación no crea allí cajas fuertes con PIN, y una caja fuerte con PIN creada antes no respeta ese límite de cinco intentos en un teléfono incautado.

## Modelo de dictado por voz

La aplicación es compatible con:
- los modelos **Whisper** GGML `.bin`: licencia MIT, origen `ggerganov/whisper.cpp`

Usted es responsable de respetar esas licencias.

## Componentes de terceros integrados en la aplicación

El dictado por voz funciona íntegramente en su teléfono, con un motor de transcripción **incluido en la aplicación**: `whisper.cpp` y `ggml`, versión 1.8.3, publicados bajo la licencia MIT. Esa licencia exige que su aviso acompañe a cada copia del software; por eso se reproduce a continuación en su versión original en inglés, y el código fuente correspondiente se encuentra en el repositorio de código fuente, en `app/src/main/cpp/vendor/whisper/`.

> MIT License
>
> Copyright (c) 2023-2024 The ggml authors
> Copyright (C) 2024 Intel Corporation
> Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Datos

Todas sus notas se guardan **localmente y cifradas** en su teléfono (consulte la **Política de privacidad**). Notes Tech no envía nada por Internet y no tiene ningún permiso técnico para hacerlo (ningún permiso `INTERNET` de Android).

## Actualizaciones

Las actualizaciones se distribuyen a través del repositorio oficial de GitHub. No hay actualización automática: le corresponde a usted instalar la nueva versión.

## Responsabilidad

El editor no puede ser considerado responsable de ningún daño directo o indirecto derivado del uso de la aplicación, dentro de los límites que permite el derecho francés (licencia Apache 2.0, sección 8). En particular, **cualquier pérdida de datos derivada de un modo pánico, una frase de contraseña olvidada, un borrado automático por errores de PIN o una desinstalación es responsabilidad exclusiva del usuario**.

## Legislación aplicable

Condiciones regidas por el **derecho francés**. En caso de litigio, serán competentes los tribunales franceses.

## Idioma

Estas condiciones se redactaron en francés; las versiones en otros idiomas son traducciones. En caso de discrepancia, **prevalece la versión francesa**.

## Contacto

**contact@files-tech.com**

---

Notes Tech forma parte de la suite **Files Tech**, publicada por **Patrice Haltaya**.
