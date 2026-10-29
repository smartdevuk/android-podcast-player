package dev.family.podcasts

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createComposeRule()
    private val show = Show("https://example.org/rss", "The Example Podcast", "Example Audio", subscribed = true)
    private val episode = Episode("episode", show.id, "A story worth getting lost in", "https://example.org/audio.mp3", date = 1792497600000, duration = 3600000, number = 3)
    private val library = Library(listOf(show), listOf(episode, episode.copy(id = "next", title = "The next story", number = 4)), mapOf(episode.id to Progress(732000, false, 10)))
    @Test fun `browsing an episode is distinct from playing it`() {
        var opened = false; var played = false
        compose.setContent { PodcastTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ListenContent(library, Playback(), emptyMap(), detail = { opened = true }, play = { played = true })
        } } }
        compose.onAllNodesWithText(episode.title).onFirst().performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(played) }
        compose.onAllNodesWithContentDescription("Play ${episode.title}").onFirst().performClick()
        compose.runOnIdle { assertTrue(played) }
        compose.onRoot().captureRoboImage("build/outputs/preview/listen.png")
    }
    @Test fun `player has explicit stop and recovery controls`() {
        compose.setContent { PodcastTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PlayerContent(Playback(episode, 732000, 3600000, playing = false, undo = 600000), library)
        } } }
        compose.onNodeWithText("Stop after this episode").assertExists()
        compose.onNodeWithContentDescription("Resume episode").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/preview/player.png")
    }
    @Test fun `discovery opens a search result without starting playback`() {
        var opened: Show? = null
        compose.setContent { PodcastTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            DiscoverContent(ScreenState(loaded = true, results = listOf(show, show.copy(url = "https://example.org/science", title = "Science Stories", author = "Example Science"))), show = { opened = it })
        } } }
        compose.onNodeWithText("Science Stories").performClick()
        compose.runOnIdle { assertEquals("Example Science", opened?.author) }
        compose.onRoot().captureRoboImage("build/outputs/preview/discover.png")
    }
    @Test fun `discovery starts with no default shows`() {
        compose.setContent { PodcastTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            DiscoverContent(ScreenState(loaded = true))
        } } }
        compose.onNodeWithText("Find your next good listen").assertIsDisplayed()
        compose.onNodeWithText("The Example Podcast").assertDoesNotExist()
        compose.onNodeWithText("Search results").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/preview/discover-empty.png")
    }
}
