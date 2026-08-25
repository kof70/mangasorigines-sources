package fr.mangasorigines.kotatsu.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.mangasorigines.kotatsu.R

/** Nothing to configure — this app exists only to host the ContentProvider Kotatsu talks to. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = getString(R.string.main_activity_body)
            textSize = 16f
            setPadding(padding, padding, padding, padding)
        }
        setContentView(text)
        ViewCompat.setOnApplyWindowInsetsListener(text) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
    }
}
