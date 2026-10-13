package io.hongshu.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
    @Test
    fun settingsBackReturnsToTheImmediatePreviousPage() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Overview))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Sync))
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
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Sync))
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
}
