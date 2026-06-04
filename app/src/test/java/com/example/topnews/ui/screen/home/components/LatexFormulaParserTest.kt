package com.example.topnews.ui.screen.home.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexFormulaParserTest {
    @Test
    fun detectsCommonArxivLatexDelimiters() {
        assertTrue(containsLatexFormula("The loss is \$L(\\theta)\$."))
        assertTrue(containsLatexFormula("The loss is \\(L(\\theta)\\)."))
        assertTrue(containsLatexFormula("\\[L(\\theta)=0\\]"))
        assertFalse(containsLatexFormula("The model costs \$5 million."))
    }

    @Test
    fun parsesInlineFormulasWithoutSurroundingWhitespace() {
        val tokens = parseLatexFormulas("Accuracy improves by \$+3.0\$ at \$23\\%\$ memory.")

        assertEquals(listOf("+3.0", "23\\%"), tokens.map { it.latex })
        assertTrue(tokens.all { !it.isBlock })
    }

    @Test
    fun parsesStandaloneDisplayFormulaAsBlock() {
        val tokens = parseLatexFormulas("Before\n\\[x^2 + y^2\\]\nAfter")

        assertEquals(1, tokens.size)
        assertEquals("x^2 + y^2", tokens.single().latex)
        assertTrue(tokens.single().isBlock)
    }

    @Test
    fun parsesInlineDoubleDollarFormulaAsInline() {
        val tokens = parseLatexFormulas("Already \$\$x^2\$\$ rendered.")

        assertEquals(1, tokens.size)
        assertEquals("x^2", tokens.single().latex)
        assertFalse(tokens.single().isBlock)
    }
}
