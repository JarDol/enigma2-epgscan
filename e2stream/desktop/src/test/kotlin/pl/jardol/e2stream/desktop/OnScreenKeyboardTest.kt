package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class OnScreenKeyboardTest {
    @Test
    fun startsOnDoneKey() {
        val text = OnScreenKeyboard.layout(digitsOnly = false, shift = false)
        val (row, col) = OnScreenKeyboard.start(text)
        assertEquals(OskAction.DONE, text[row][col].action)
        val digits = OnScreenKeyboard.layout(digitsOnly = true, shift = false)
        val (dr, dc) = OnScreenKeyboard.start(digits)
        assertEquals(OskAction.DONE, digits[dr][dc].action)
    }

    @Test
    fun horizontalMoveWraps() {
        val layout = OnScreenKeyboard.layout(digitsOnly = true, shift = false)
        assertEquals(0 to 2, OnScreenKeyboard.move(layout, 0, 0, 0, -1))
        assertEquals(0 to 0, OnScreenKeyboard.move(layout, 0, 2, 0, 1))
    }

    @Test
    fun verticalMoveKeepsColumnAndWraps() {
        val layout = OnScreenKeyboard.layout(digitsOnly = false, shift = false)
        assertEquals(2 to 9, OnScreenKeyboard.move(layout, 1, 9, 1, 0))
        assertEquals(layout.lastIndex to 0, OnScreenKeyboard.move(layout, 0, 0, -1, 0))
        // z „Spacja” w górę — klawisz nad środkiem spacji
        val up = OnScreenKeyboard.move(layout, layout.lastIndex, 1, -1, 0)
        assertEquals(layout.lastIndex - 1, up.first)
        assertEquals(OskAction.CHAR, layout[up.first][up.second].action)
    }

    @Test
    fun shiftUppercasesPolishLetters() {
        val layout = OnScreenKeyboard.layout(digitsOnly = false, shift = true)
        assertEquals("Ą", layout[4][0].text)
        assertEquals("Q", layout[1][0].text)
    }
}
