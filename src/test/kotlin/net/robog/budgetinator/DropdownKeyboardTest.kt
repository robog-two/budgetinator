package net.robog.budgetinator

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import net.robog.budgetinator.ui.Dropdown
import net.robog.budgetinator.ui.Field
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DropdownKeyboardTest {
    private val options = listOf("Pending", "Approved", "Denied")

    private fun ComposeUiTest.setUp(): () -> String? {
        var picked by mutableStateOf<String?>(null)
        setContent {
            MaterialTheme {
                Column {
                    Field("Before", "", {})
                    Dropdown("Status", picked, options, { it }, { picked = it })
                }
            }
        }
        return { picked }
    }

    @Test fun `tab enter arrows enter selects an option`() = runComposeUiTest {
        val picked = setUp()
        onNodeWithText("Before").performClick()
        onNodeWithText("Before").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Status").assertIsFocused()
        onNodeWithText("Status").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("Approved").assertExists()
        onNodeWithText("Approved").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("Approved", picked())
        onNodeWithText("Denied").assertDoesNotExist()
        onNodeWithText("Status").assertIsFocused()
    }

    @Test fun `space opens and escape closes without choosing`() = runComposeUiTest {
        val picked = setUp()
        onNodeWithText("Before").performClick()
        onNodeWithText("Before").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Status").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        onNodeWithText("Pending").assertIsFocused()
        onNodeWithText("Pending").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("Denied").assertDoesNotExist()
        onNodeWithText("Status").assertIsFocused()
        assertEquals(null, picked())
    }

    @Test fun `mouse click opens the menu`() = runComposeUiTest {
        val picked = setUp()
        onNodeWithText("Status").performClick()
        onNodeWithText("Denied").performClick()
        waitForIdle()
        assertEquals("Denied", picked())
    }
}
