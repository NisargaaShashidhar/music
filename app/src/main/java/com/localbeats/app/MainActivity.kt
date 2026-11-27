package com.localbeats.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val accent = 0xFFB39DFF.toInt()
    private val grey = 0xFFB0B0B0.toInt()

    private lateinit var store: Store
    private var svc: PlayerService? = null
    private var songs: List<Song> = emptyList()
    private var mode = 0 // 0 = songs, 1 = playlists, 2 = inside a playlist
    private var openPl: String? = null
    private var pendingSongId = -1L
    private var tracking = false
    private val rows = ArrayList<Row>()
    private val handler = Handler(Looper.getMainLooper())
    private val artCache = LruCache<Long, Bitmap>(80)
    private val adapter = Ad()

    private lateinit var bgView: ImageView
    private lateinit var titleTv: TextView
    private lateinit var addBtn: TextView
    private lateinit var tabSongs: TextView
    private lateinit var tabPl: TextView
    private lateinit var list: ListView
    private lateinit var emptyTv: TextView
    private lateinit var bar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var barArt: ImageView
    private lateinit var barTitle: TextView
    private lateinit var barArtist: TextView
    private lateinit var playBtn: ImageButton

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as PlayerService.LocalBinder).get()
            svc?.listener = {
                updateBar()
                adapter.notifyDataSetChanged()
            }
            updateBar()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = svc
            if (s != null && s.current != null && !tracking) seek.progress = s.position
            handler.postDelayed(this, 500)
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun artFile(id: Long) = File(filesDir, "art/$id.jpg")
    private fun bgFile() = File(filesDir, "bg.jpg")

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun ib(res: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setBackgroundColor(0)
        setColorFilter(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun getArt(id: Long): Bitmap? {
        artCache.get(id)?.let { return it }
        val f = artFile(id)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = BitmapFactory.decodeFile(f.path, o) ?: return null
        artCache.put(id, b)
        return b
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        applyBg()
        val si = Intent(this, PlayerService::class.java)
        startService(si)
        bindService(si, conn, BIND_AUTO_CREATE)
        ensurePerms()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        updateBar()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        svc?.listener = null
        val playing = svc?.isPlaying == true
        try { unbindService(conn) } catch (e: Exception) { }
        if (!playing) stopService(Intent(this, PlayerService::class.java))
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (mode == 2) {
            mode = 1
            refresh()
        } else super.onBackPressed()
    }

    // ---------- permissions + music scan ----------
    private fun audioPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPerm() = checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED

    private fun ensurePerms() {
        val need = ArrayList<String>()
        if (!hasAudioPerm()) need.add(audioPerm())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) loadSongs() else requestPermissions(need.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAudioPerm()) loadSongs() else refresh()
    }

    private fun loadSongs() {
        val out = ArrayList<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION
        )
        try {
            contentResolver.query(
                uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        Song(
                            id, c.getString(1) ?: "Unknown", c.getString(2) ?: "Unknown artist",
                            ContentUris.withAppendedId(uri, id), c.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        songs = out
        refresh()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121212.toInt())

        bgView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))
        val overlay = View(this).apply { setBackgroundColor(0xBF000000.toInt()) }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleTv = text("LocalBeats", 24f, Color.WHITE, true)
        header.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        addBtn = text("＋", 26f, Color.WHITE).apply {
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { newPlaylist { n -> createPlaylist(n) } }
        }
        header.addView(addBtn)
        val menuBtn = text("⋮", 26f, Color.WHITE).apply {
            setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener { showMenu() }
        }
        header.addView(menuBtn)
        col.addView(header)

        // tabs
        val tabs = LinearLayout(this)
        tabSongs = text("SONGS", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { mode = 0; refresh() }
        }
        tabPl = text("PLAYLISTS", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { mode = 1; refresh() }
        }
        tabs.addView(tabSongs, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabPl, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(tabs)

        // list
        list = ListView(this).apply {
            divider = null
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onRowLong(rows[pos]); true }
        }
        emptyTv = text("", 16f, grey).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        list.emptyView = emptyTv
        col.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // mini player
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE61E1B2E.toInt())
            visibility = View.GONE
        }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) svc?.seekTo(p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(s: SeekBar?) { tracking = false }
            })
        }
        bar.addView(seek, LinearLayout.LayoutParams(-1, -2))
        val barRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), dp(6))
        }
        barArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        barRow.addView(barArt, LinearLayout.LayoutParams(dp(48), dp(48)))
        val tcol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        barTitle = text("", 15f, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        barArtist = text("", 12f, grey).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tcol.addView(barTitle)
        tcol.addView(barArtist)
        barRow.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        barRow.addView(ib(android.R.drawable.ic_media_previous) { svc?.prev() })
        playBtn = ib(android.R.drawable.ic_media_play) { svc?.toggle() }
        barRow.addView(playBtn)
        barRow.addView(ib(android.R.drawable.ic_media_next) { svc?.next() })
        bar.addView(barRow)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun applyBg() {
        val f = bgFile()
        if (f.exists()) bgView.setImageBitmap(BitmapFactory.decodeFile(f.path))
        else bgView.setImageDrawable(null)
    }

    private fun refresh() {
        rows.clear()
        val pls = store.playlists()
        when (mode) {
            0 -> for (s in songs) rows.add(Row(s.title, s.artist, s, null))
            1 -> for ((n, ids) in pls) rows.add(Row(n, "${ids.size} songs", null, n))
            2 -> {
                val ids = pls[openPl] ?: ArrayList<Long>()
                for (id in ids) songs.firstOrNull { it.id == id }?.let { rows.add(Row(it.title, it.artist, it, openPl)) }
            }
        }
        titleTv.text = if (mode == 2) (openPl ?: "") else "LocalBeats"
        addBtn.visibility = if (mode == 1) View.VISIBLE else View.GONE
        val onSongs = mode == 0
        tabSongs.setTextColor(if (onSongs) accent else grey)
        tabPl.setTextColor(if (!onSongs) accent else grey)
        emptyTv.text = when {
            !hasAudioPerm() -> "Allow access to your music to see your songs.\nTap ⋮ → Rescan music after allowing."
            mode == 0 -> "No music found on this phone."
            mode == 1 -> "No playlists yet. Tap ＋ to create one, or long-press a song → Add to playlist."
            else -> "This playlist is empty. Long-press a song in Songs → Add to playlist."
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateBar() {
        val s = svc?.current
        if (s == null) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        barTitle.text = s.title
        barArtist.text = s.artist
        val art = getArt(s.id)
        if (art != null) barArt.setImageBitmap(art) else barArt.setImageResource(R.drawable.icon)
        playBtn.setImageResource(
            if (svc?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        seek.max = s.duration.toInt().coerceAtLeast(1)
        seek.progress = svc?.position ?: 0
    }

    inner class Ad : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val r = rows[i]
            val row = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            val img = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            }
            val bmp = r.song?.let { getArt(it.id) }
            if (bmp != null) img.setImageBitmap(bmp) else img.setImageResource(R.drawable.icon)
            row.addView(img)
            val tc = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            val playing = r.song != null && svc?.current?.id == r.song.id
            tc.addView(text(r.title, 16f, if (playing) accent else Color.WHITE, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            tc.addView(text(r.sub, 13f, grey).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }
    }

    // ---------- row actions ----------
    private fun onRowClick(r: Row) {
        val s = r.song
        if (s != null) {
            val q = rows.mapNotNull { it.song }
            val sv = svc
            if (sv == null) toast("Player is starting, try again")
            else sv.playQueue(q, q.indexOfFirst { it.id == s.id }.coerceAtLeast(0))
        } else if (r.playlist != null) {
            mode = 2
            openPl = r.playlist
            refresh()
        }
    }

    private fun onRowLong(r: Row) {
        val s = r.song
        if (s != null) {
            val opts = ArrayList<String>()
            opts.add("Set artwork from gallery")
            if (artFile(s.id).exists()) opts.add("Remove artwork")
            opts.add("Add to playlist")
            if (mode == 2) opts.add("Remove from this playlist")
            AlertDialog.Builder(this).setTitle(s.title).setItems(opts.toTypedArray()) { _, w ->
                when (opts[w]) {
                    "Set artwork from gallery" -> {
                        pendingSongId = s.id
                        pickImage(1)
                    }
                    "Remove artwork" -> {
                        artFile(s.id).delete()
                        artCache.remove(s.id)
                        adapter.notifyDataSetChanged()
                        updateBar()
                    }
                    "Add to playlist" -> addToPlaylist(s.id)
                    "Remove from this playlist" -> {
                        val pls = store.playlists()
                        pls[openPl]?.remove(s.id)
                        store.savePlaylists(pls)
                        refresh()
                    }
                }
            }.show()
        } else if (r.playlist != null) {
            AlertDialog.Builder(this).setTitle(r.playlist)
                .setItems(arrayOf("Delete playlist")) { _, _ ->
                    val pls = store.playlists()
                    pls.remove(r.playlist)
                    store.savePlaylists(pls)
                    refresh()
                }.show()
        }
    }

    private fun newPlaylist(cb: (String) -> Unit) {
        val et = EditText(this).apply {
            hint = "Playlist name"
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("New playlist").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) cb(n)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun createPlaylist(name: String) {
        val pls = store.playlists()
        if (!pls.containsKey(name)) pls[name] = ArrayList<Long>()
        store.savePlaylists(pls)
        refresh()
    }

    private fun addToPlaylist(songId: Long) {
        val pls = store.playlists()
        val names = pls.keys.toList()
        val labels = names + "＋ New playlist"
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(labels.toTypedArray()) { _, w ->
            if (w < names.size) {
                val l = pls[names[w]]!!
                if (!l.contains(songId)) l.add(songId)
                store.savePlaylists(pls)
                toast("Added to ${names[w]}")
                if (mode == 1) refresh()
            } else {
                newPlaylist { n ->
                    val l = pls.getOrPut(n) { ArrayList<Long>() }
                    if (!l.contains(songId)) l.add(songId)
                    store.savePlaylists(pls)
                    toast("Added to $n")
                    refresh()
                }
            }
        }.show()
    }

    // ---------- customisation ----------
    private fun showMenu() {
        val opts = arrayOf(
            "Choose app background photo",
            "Remove background photo",
            "Create home-screen shortcut with a photo",
            "Rescan music"
        )
        AlertDialog.Builder(this).setItems(opts) { _, w ->
            when (w) {
                0 -> pickImage(2)
                1 -> { bgFile().delete(); applyBg() }
                2 -> pickImage(3)
                3 -> if (hasAudioPerm()) loadSongs() else ensurePerms()
            }
        }.show()
    }

    private fun pickImage(code: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        startActivityForResult(Intent.createChooser(i, "Choose a photo"), code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            1 -> {
                if (Img.save(this, uri, artFile(pendingSongId), 600)) {
                    artCache.remove(pendingSongId)
                    adapter.notifyDataSetChanged()
                    updateBar()
                    toast("Artwork set")
                } else toast("Couldn't load that photo")
            }
            2 -> {
                if (Img.save(this, uri, bgFile(), 1280)) {
                    applyBg()
                    toast("Background set")
                } else toast("Couldn't load that photo")
            }
            3 -> createShortcut(uri)
        }
    }

    private fun createShortcut(uri: Uri) {
        val tmp = File(cacheDir, "shortcut.jpg")
        if (!Img.save(this, uri, tmp, 256)) {
            toast("Couldn't load that photo")
            return
        }
        val bm = BitmapFactory.decodeFile(tmp.path) ?: return
        val s = minOf(bm.width, bm.height)
        val square = Bitmap.createBitmap(bm, (bm.width - s) / 2, (bm.height - s) / 2, s, s)
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Your launcher doesn't support home-screen shortcuts")
            return
        }
        val launch = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val info = ShortcutInfo.Builder(this, "localbeats_" + System.currentTimeMillis())
            .setShortLabel("LocalBeats")
            .setIcon(Icon.createWithBitmap(square))
            .setIntent(launch)
            .build()
        sm.requestPinShortcut(info, null)
    }
}
