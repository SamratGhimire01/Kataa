package app.kataa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.kataa.KataaApplication
import app.kataa.core.assistant.Reply
import app.kataa.core.assistant.TimeOption
import app.kataa.core.nlu.Secret
import app.kataa.core.nlu.Understanding
import app.kataa.places.MapActivity
import app.kataa.core.model.Memory
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.Source
import app.kataa.core.session.KataaState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Opens the real main screen on the JVM, drives it through the main moments, and saves a picture of
 * each to build/screens/. Fails if any screen crashes. (The database isn't available here, so lists
 * show their empty states.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensRenderTest {
    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }

    @Suppress("UNCHECKED_CAST")
    private fun setState(app: KataaApplication, s: KataaState) {
        val f = app.session.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        (f.get(app.session) as MutableStateFlow<KataaState>).value = s
    }

    private fun shot(root: View, name: String) {
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun memory(body: String, kind: MemoryKind = MemoryKind.OBJECT_LOCATION) = Memory(
        1, "u-1", kind, body, body, null, Source.VOICE, null, "en", "keys", null,
        System.currentTimeMillis() - 120_000, System.currentTimeMillis(), null, false, null,
    )

    private fun run(look: Look, tag: String) {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Look.save(ctx, look)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        val app = act.application as KataaApplication
        val root = act.window.decorView
        idle(1500); shot(root, "$tag-1-home")
        setState(app, KataaState.Listening("my keys are in the blue", 0.6f)); idle(900); shot(root, "$tag-2-listening")
        setState(app, KataaState.Thinking("my keys are in the blue drawer")); idle(500)
        setState(app, KataaState.Done("my keys are in the blue drawer", Reply.Saved(memory("My keys are in the blue drawer")))); idle(2200); shot(root, "$tag-3-saved")
        setState(app, KataaState.Done("where are my keys?", Reply.Answer("In the blue drawer", memory("My keys are in the blue drawer"), emptyList(), emptyList()))); idle(1800); shot(root, "$tag-4-answer")
        act.javaClass.getDeclaredMethod("openMemories").apply { isAccessible = true }.invoke(act); idle(1500); shot(root, "$tag-5-memories")
        act.javaClass.getDeclaredMethod("openSettings").apply { isAccessible = true }.invoke(act); idle(1200); shot(root, "$tag-6-settings")
        act.javaClass.getDeclaredMethod("openReminders", String::class.java).apply { isAccessible = true }.invoke(act, null); idle(1200); shot(root, "$tag-7-plan")
        act.javaClass.getDeclaredMethod("openList", String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(act, "day:2026-11-24", "0 of 3 done, 3 left."); idle(1200); shot(root, "$tag-8-list")
        act.javaClass.getDeclaredMethod("openJournal", kotlinx.datetime.LocalDate::class.java).apply { isAccessible = true }
            .invoke(act, kotlinx.datetime.LocalDate(2026, 11, 24)); idle(1200); shot(root, "$tag-9-journal")
        act.javaClass.getDeclaredMethod("openTimeline", kotlinx.datetime.LocalDate::class.java).apply { isAccessible = true }.invoke(act, null); idle(1200); shot(root, "$tag-10-everything")
        val pending = Understanding.Save("Wifi password is sunflower123", MemoryKind.FACT, "wifi password", null, true, Secret("Wifi", "sunflower123"))
        setState(app, KataaState.Done("my wifi password is sunflower123", Reply.ConfirmSecret("Wifi", "sunflower123", null, pending, "my wifi password is sunflower123"))); idle(1500); shot(root, "$tag-11-password-check")
        setState(app, KataaState.Done("call didi at 7", Reply.PickTime(memory("Call didi", MemoryKind.REMINDER), listOf(
            TimeOption(0, "Today at 7:00 PM"), TimeOption(1, "Tomorrow at 7:00 AM")), false))); idle(1500); shot(root, "$tag-12-am-pm")
        setState(app, KataaState.Done("i've got an idea", Reply.ListeningForIdea)); idle(800); shot(root, "$tag-13-idea")
        controller.pause().stop().destroy()
        val map = Robolectric.buildActivity(MapActivity::class.java).setup()
        idle(1500); shot(map.get().window.decorView, "$tag-14-map")
        map.pause().stop().destroy()
    }

    @Test fun neon() = run(Look.NEON, "neon")
    @Test fun saffron() = run(Look.SAFFRON, "saffron")
    @Test fun pastel() = run(Look.PASTEL, "pastel")
}
