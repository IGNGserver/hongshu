package io.hongshu.app

import androidx.compose.ui.unit.dp
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
        val first = AppDestination.Conversation("100")
        val second = AppDestination.Conversation("300")

        val stack = replaceTopDestination(pushDestination(home, first), second)

        assertEquals(listOf(AppDestination.Home, second), stack)
    }

    @Test
    fun closingSettingsKeepsTheDestinationThatOpenedIt() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = pushDestination(stack, AppDestination.Conversation("100"))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Overview))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Capture))
        stack = pushDestination(stack, AppDestination.Settings(SettingsPage.Pending))

        stack = closeSettingsDestinations(stack)

        assertEquals(
            listOf<AppDestination>(
                AppDestination.Home,
                AppDestination.Conversation("100"),
            ),
            stack,
        )
    }

    @Test
    fun searchAndTwoPaneConversationsShareTheMainScreen() {
        val conversation = AppDestination.Conversation("100")
        val overview = AppDestination.Settings(SettingsPage.Overview)
        val capture = AppDestination.Settings(SettingsPage.Capture)

        assertEquals(AppDestination.Home.screenKey(false), AppDestination.Search.screenKey(false))
        assertEquals(AppDestination.Home.screenKey(true), conversation.screenKey(true))
        assertNotEquals(AppDestination.Home.screenKey(false), conversation.screenKey(false))
        assertEquals(overview.screenKey(true), capture.screenKey(true))
        assertNotEquals(overview.screenKey(false), capture.screenKey(false))
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

    @Test
    fun adaptiveLayoutSpecCoversCompactMediumAndExpanded() {
        val compact = adaptiveLayoutSpec(width = 400.dp, height = 880.dp)
        assertFalse(compact.twoPane)
        assertFalse(compact.expanded)
        assertFalse(compact.compactHeight)
        assertEquals(320.dp, compact.bubbleMaxWidth)

        val phoneLandscape = adaptiveLayoutSpec(width = 720.dp, height = 380.dp)
        assertTrue(phoneLandscape.twoPane)
        assertFalse(phoneLandscape.expanded)
        assertTrue(phoneLandscape.compactHeight)
        assertTrue(phoneLandscape.listPaneWidth in 300.dp..348.dp)
        assertEquals(460.dp, phoneLandscape.bubbleMaxWidth)

        val tabletLandscape = adaptiveLayoutSpec(width = 1024.dp, height = 640.dp)
        assertTrue(tabletLandscape.twoPane)
        assertTrue(tabletLandscape.expanded)
        assertFalse(tabletLandscape.compactHeight)
        assertTrue(tabletLandscape.listPaneWidth in 360.dp..420.dp)
        assertEquals(460.dp, tabletLandscape.bubbleMaxWidth)
    }

    @Test
    fun twoPaneSearchKeepsSearchModeWhenOpeningConversation() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = updateSearchDestination(stack, active = true)
        assertTrue(isSearchMode(stack, twoPane = true))
        assertTrue(isSearchMode(stack, twoPane = false))

        stack = pushDestination(stack, AppDestination.Conversation("95588"))
        assertTrue(isSearchMode(stack, twoPane = true))
        assertFalse(isSearchMode(stack, twoPane = false))

        // Exiting search from the left pane while a conversation is open in the right pane
        // drops Search and keeps the open conversation.
        val exited = updateSearchDestination(stack, active = false)
        assertEquals(listOf(AppDestination.Home, AppDestination.Conversation("95588")), exited)
        assertFalse(isSearchMode(exited, twoPane = true))
    }

    @Test
    fun twoPaneSettingsReplacesSubpageAndClosesCleanlyOnBack() {
        var stack = listOf<AppDestination>(AppDestination.Home)
        stack = navigateSettingsDestination(stack, SettingsPage.Overview, twoPane = true)
        assertEquals(listOf(AppDestination.Home, AppDestination.Settings(SettingsPage.Overview)), stack)

        stack = navigateSettingsDestination(stack, SettingsPage.Capture, twoPane = true)
        assertEquals(
            listOf(
                AppDestination.Home,
                AppDestination.Settings(SettingsPage.Overview),
                AppDestination.Settings(SettingsPage.Capture),
            ),
            stack,
        )

        stack = navigateSettingsDestination(stack, SettingsPage.Sims, twoPane = true)
        assertEquals(
            listOf(
                AppDestination.Home,
                AppDestination.Settings(SettingsPage.Overview),
                AppDestination.Settings(SettingsPage.Sims),
            ),
            stack,
        )

        stack = popNavigationStack(stack, twoPane = true)
        assertEquals(listOf<AppDestination>(AppDestination.Home), stack)
    }
}
