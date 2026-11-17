package com.filestech.notes_tech.ui.about

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * D-050 (3.5.0): the folded hands' fill brought close to WHITE (Patrice, 2026-11-17: "fond blanc avec un
 * contour gris"), their inner lines kept faint. The matrix is applied here by hand, as Android applies it:
 * R' = m0·R + m1·G + m2·B + m3·A + m4, components and offset in 0-255.
 */
class VersLeBlancTest {

    private val plancher = 0.72f

    private fun applique(couleur: FloatArray): FloatArray {
        val m = versLeBlanc(plancher).values
        val en255 = FloatArray(4) { couleur[it] * 255f }
        return FloatArray(4) { ligne ->
            (0 until 4).sumOf { colonne -> (m[ligne * 5 + colonne] * en255[colonne]).toDouble() }.toFloat() +
                m[ligne * 5 + 4]
        }.map { it / 255f }.toFloatArray()
    }

    @Test
    fun `white stays white`() {
        val r = applique(floatArrayOf(1f, 1f, 1f, 1f))
        for (i in 0 until 3) assertThat(r[i]).isWithin(EPS).of(1f)
    }

    @Test
    fun `black becomes the floor, a light grey — never darker`() {
        val r = applique(floatArrayOf(0f, 0f, 0f, 1f))
        for (i in 0 until 3) assertThat(r[i]).isWithin(EPS).of(plancher)
    }

    @Test
    fun `no colour of its own is left, and lighter stays lighter`() {
        val rouge = applique(floatArrayOf(1f, 0f, 0f, 1f))
        assertThat(rouge[0]).isWithin(EPS).of(rouge[1])
        assertThat(rouge[1]).isWithin(EPS).of(rouge[2])
        val jaune = applique(floatArrayOf(1f, 0.85f, 0.3f, 1f))
        val orange = applique(floatArrayOf(0.9f, 0.5f, 0.1f, 1f))
        assertThat(jaune[0]).isGreaterThan(orange[0])
    }

    @Test
    fun `alpha is kept as it is`() {
        assertThat(applique(floatArrayOf(1f, 0.3f, 0.2f, 0.35f))[3]).isWithin(EPS).of(0.35f)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
