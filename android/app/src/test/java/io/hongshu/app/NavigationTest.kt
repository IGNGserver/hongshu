package io.hongshu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun settingsBackReturnsToTheImmediatePreviousPage() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Overview))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Capture))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Pending))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Sims))

        stack = popDestination(stack)

        assertEquals(AppDestination.Settings(SettingsPage.Pending), stack.last())
    }

    @Test
    fun selectingAnotherConversationReplacesTheCurrentConversation() {
        val home = listOf<AppDestination>(AppDestination.Home)
        val first = AppDestination.Conversation("100", "200")
        val second = AppDestination.Conversation("300", "400")

        val stack = replaceTopDestination(pushDestination(home, first), second)

        assertEquals(listOf(AppDestination.Home, second), stack)
    }

    @Test
    fun closingSettingsKeepsTheDestinationThatOpenedIt() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = pushDestination(stack, AppDestination.Conversation("100", "200"))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Overview))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Capture))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Pending))

        stack = closeSettingsDestinations(stack)

        assertEquals(
            listOf<AppDestination>(
                AppDestination.Home,
                AppDestination.Conversation("100", "200"),
            ),
            stack,
        )
    }

    @Test
    fun searchAndTwoPaneConversationsShareTheMainScreen() {
        val conversation = AppDestination.Conversation("100", "200")

        assertEquals(AppDestination.Home.screenKey(false), AppDestination.Search.screenKey(false))
        assertEquals(AppDestination.Home.screenKey(true), conversation.screenKey(true))
        assertNotEquals(AppDestination.Home.screenKey(false), conversation.screenKey(false))
    }

    @Test
    fun poppedDestinationsAnimateBackward() {
        val overview = AppDestination.Settings(SettingsPage.Overview)
        val capture = AppDestination.Settings(SettingsPage.Capture)
        val stack = listOf(AppDestination.Home, overview, capture)

        assertFalse(isBackNavigation(stack, overview, capture))
        assertTrue(isBackNavigation(stack, capture, overview))
        // After a pop the leaving page is no longer on the stack.
        assertTrue(isBackNavigation(listOf(AppDestination.Home, overview), capture, overview))
        // Closing settings jumps straight back home.
        assertTrue(isBackNavigation(listOf(AppDestination.Home), capture, AppDestination.Home))
    }
}
