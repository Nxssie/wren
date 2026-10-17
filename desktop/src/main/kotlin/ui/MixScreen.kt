package ui

import analysis.LocalAnalysisProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mix.Affinity
import mix.Discovery
import mix.MixAnalysis
import mix.MixDiscovery
import mix.MixFilters
import mix.MixRanking
import mix.MixSet
import mix.MixStore
import mix.MixSuggestions
import mix.MixTrack
import mix.toMixTrack
import models.SearchResult
import player.FFmpegPlayer
import provider.MusicProvider

private val TOLERANCES = listOf(3.0, 6.0, 9.0, 12.0)

/**
 * The DJ view: one hand-built set on the left, what to play next on the right.
 *
 * Separate from the Library on purpose — a set is an ordered selection of its own, not a view over
 * saved music. Everything here is derived from the tracks' tempo and Camelot slot, so a set whose
 * tracks have neither simply shows why instead of guessing.
 */
@Composable
fun MixScreen(provider: MusicProvider, player: FFmpegPlayer) {
    val scope = rememberCoroutineScope()
    val lookupProvider = remember { LocalAnalysisProvider() }

    var sets by remember { mutableStateOf<List<MixSet>>(emptyList()) }
    var selectedSetId by remember { mutableStateOf<String?>(null) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var tolerance by remember { mutableStateOf(6.0) }
    var harmonicsOnly by remember { mutableStateOf(true) }
    var foldTempo by remember { mutableStateOf(true) }

    suspend fun reload() {
        sets = withContext(Dispatchers.IO) { runCatching { MixStore.sets() }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(sets) {
        if (selectedSetId == null || sets.none { it.id == selectedSetId }) {
            selectedSetId = sets.firstOrNull()?.id
        }
    }

    val set = sets.firstOrNull { it.id == selectedSetId }
    val anchor = set?.let { s -> s.track(anchorId ?: "") ?: s.tracks.firstOrNull() }

    LaunchedEffect(set?.id) { anchorId = set?.tracks?.firstOrNull()?.id }

    val filters = MixFilters(
        bpmTolerancePercent = tolerance,
        requireHarmonic = harmonicsOnly,
        allowTempoFolding = foldTempo
    )
    val inSet = remember(set, anchor, filters) {
        anchor?.let { MixSuggestions.rank(it, set.tracks, filters) }
    }
    // Ranked against the anchor when there is one, offered as they came otherwise: this pane is the
    // only way to put a track into a set, so it cannot depend on the set already holding one.
    val discovered = remember(results, anchor, filters) {
        MixDiscovery.of(anchor, results.map { it.toMixTrack() }, filters)
    }
    // What the user already has elsewhere that mixes with the anchor: the set being edited is
    // excluded, because its own tracks are the in-set list just above.
    val repertoire = remember(sets, set?.id, anchor, filters) {
        MixDiscovery.repertoire(sets, set?.id, anchor, filters)
    }

    fun mutate(block: () -> List<MixSet>) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(sets) }
            sets = updated
        }
    }

    fun detect() {
        val current = set ?: return
        if (detecting) return
        scope.launch {
            detecting = true
            withContext(Dispatchers.IO) {
                current.tracks.forEach { track ->
                    // A track with a tempo but no key counts as analysed, so it has to be refilled
                    // explicitly or its missing half would never be asked for again.
                    val incomplete = track.bpm == null || track.key == null
                    val answer = if (incomplete) MixAnalysis.refill(track, lookupProvider)
                    else MixAnalysis.resolve(track, lookupProvider)
                    if (answer != null) {
                        // Keep the half that is already known when only the other one came back.
                        MixStore.recordAnalysis(
                            current.id,
                            track.id,
                            answer.bpm ?: track.bpm,
                            answer.camelot?.toString() ?: track.camelot
                        )
                    }
                }
            }
            reload()
            detecting = false
        }
    }

    /**
     * Puts [track] in the set and, when it carries no analysis yet, runs the one decode that makes
     * it scoreable. Without that the new row sits at "—" until someone presses detect.
     */
    fun addToSet(track: MixTrack) {
        val current = set ?: return
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching { MixStore.addTrack(current.id, track) }.getOrDefault(sets)
            }
            sets = updated
            if (track.isAnalysed) return@launch

            detecting = true
            val analysed = withContext(Dispatchers.IO) {
                val answer = MixAnalysis.refill(track, lookupProvider) ?: return@withContext null
                runCatching {
                    MixStore.recordAnalysis(current.id, track.id, answer.bpm, answer.camelot?.toString())
                }.getOrNull()
            }
            if (analysed != null) sets = analysed
            detecting = false
        }
    }

    fun discover() {
        if (query.isBlank() || searching) return
        scope.launch {
            searching = true
            results = runCatching { provider.search(query) }.getOrDefault(emptyList())
            searching = false
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        SetPane(
            modifier = Modifier.weight(0.42f).fillMaxHeight(),
            sets = sets,
            set = set,
            anchorId = anchor?.id,
            player = player,
            onCreate = { name ->
                scope.launch {
                    val created = withContext(Dispatchers.IO) { runCatching { MixStore.create(name) }.getOrNull() }
                    reload()
                    if (created != null) { selectedSetId = created.id; anchorId = null }
                }
            },
            onSelect = { id -> selectedSetId = id; anchorId = null },
            onDelete = { mutate { MixStore.delete(it) }; selectedSetId = null },
            onAnchor = { anchorId = it },
            onMove = { trackId, offset -> mutate { MixStore.moveTrack(set!!.id, trackId, offset) } },
            onRemove = { trackId -> mutate { MixStore.removeTrack(set!!.id, trackId) } }
        )

        Spacer(Modifier.width(24.dp))

        NextPane(
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
            anchor = anchor,
            inSet = inSet,
            discovered = discovered,
            repertoire = repertoire,
            detecting = detecting,
            lookupConfigured = lookupProvider.isConfigured,
            providerLabel = provider.platform.label,
            tolerance = tolerance,
            harmonicsOnly = harmonicsOnly,
            foldTempo = foldTempo,
            onTolerance = { tolerance = it },
            onHarmonics = { harmonicsOnly = it },
            onFold = { foldTempo = it },
            onDetect = ::detect,
            onPlay = { track ->
                val tracks = set?.tracks ?: return@NextPane
                val index = tracks.indexOfFirst { it.id == track.id }
                if (index >= 0) player.loadQueue(tracks.map { it.toQueueItem() }, index)
            },
            onAdd = ::addToSet,
            onAnchor = { anchorId = it.id },
            query = query,
            onQuery = { query = it },
            searching = searching,
            onSearch = ::discover
        )
    }
}

@Composable
private fun SetPane(
    modifier: Modifier,
    sets: List<MixSet>,
    set: MixSet?,
    anchorId: String?,
    player: FFmpegPlayer,
    onCreate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAnchor: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit
) {
    var draftName by remember { mutableStateOf("") }
    var setsExpanded by remember { mutableStateOf(false) }

    Column(modifier) {
        Text("_set;", color = PsPearl300.copy(alpha = 0.6f), fontFamily = FontMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .background(PsInset)
                        .clickable(enabled = sets.isNotEmpty()) { setsExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        set?.name ?: "no set yet",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = PsSteel500, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(
                    expanded = setsExpanded,
                    onDismissRequest = { setsExpanded = false },
                    modifier = Modifier.background(Surface)
                ) {
                    sets.forEach { s ->
                        DropdownMenuItem(onClick = { setsExpanded = false; onSelect(s.id) }) {
                            Text(s.name, color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (set != null) {
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { onDelete(set.id) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Delete set", tint = PsSignalDanger, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                placeholder = { Text("_new_set;", color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f).onKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyUp && draftName.isNotBlank()) {
                        onCreate(draftName); draftName = ""; true
                    } else false
                },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = TextPrimary,
                    cursorColor = TextPrimary,
                    focusedBorderColor = TextPrimary,
                    unfocusedBorderColor = PsPearl200,
                    backgroundColor = Surface,
                    placeholderColor = PsSteel400
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { if (draftName.isNotBlank()) { onCreate(draftName); draftName = "" } },
                modifier = Modifier.size(44.dp).background(PsInk900)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create set", tint = PsWhite)
            }
        }

        Spacer(Modifier.height(14.dp))

        val tracks = set?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            Text(
                if (set == null) "create a set to start ordering tracks" else "add tracks from the search below",
                color = TextSecondary,
                fontFamily = FontMono,
                fontSize = 12.sp
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    SetRow(
                        track = track,
                        isAnchor = track.id == anchorId,
                        canPlay = true,
                        onAnchor = { onAnchor(track.id) },
                        onPlay = {
                            val index = tracks.indexOfFirst { it.id == track.id }
                            if (index >= 0) player.loadQueue(tracks.map { it.toQueueItem() }, index)
                        },
                        onMove = onMove,
                        onRemove = onRemove
                    )
                }
            }
        }
    }
}

@Composable
private fun SetRow(
    track: MixTrack,
    isAnchor: Boolean,
    canPlay: Boolean,
    onAnchor: () -> Unit,
    onPlay: () -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (isAnchor) {
                    drawRect(color = PsIrisCyan, topLeft = Offset(0f, 0f), size = Size(3.dp.toPx(), size.height))
                    drawRect(color = PsInset, topLeft = Offset(0f, 0f), size = size)
                }
                drawLine(
                    color = HairlineSoft,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .clickable { onAnchor() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isAnchor) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        AnalysisChip(track)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onPlay, enabled = canPlay, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play set from here", tint = PsSteel400, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = { onMove(track.id, -1) }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", tint = PsSteel400, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = { onMove(track.id, +1) }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", tint = PsSteel400, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = { onRemove(track.id) }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.Clear, contentDescription = "Remove from set", tint = PsSteel400, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun AnalysisChip(track: MixTrack) {
    val label = track.analysisLabel

    Text(
        label,
        color = if (label == "unknown") PsSteel400 else PsInk900,
        fontFamily = FontMono,
        fontSize = 10.sp,
        modifier = Modifier
            .background(if (label == "unknown") Color.Transparent else PsIrisCyan)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun NextPane(
    modifier: Modifier,
    anchor: MixTrack?,
    inSet: MixRanking?,
    discovered: Discovery,
    repertoire: MixRanking?,
    detecting: Boolean,
    lookupConfigured: Boolean,
    providerLabel: String,
    tolerance: Double,
    harmonicsOnly: Boolean,
    foldTempo: Boolean,
    onTolerance: (Double) -> Unit,
    onHarmonics: (Boolean) -> Unit,
    onFold: (Boolean) -> Unit,
    onDetect: () -> Unit,
    onPlay: (MixTrack) -> Unit,
    onAdd: (MixTrack) -> Unit,
    onAnchor: (MixTrack) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit
) {
    Column(modifier) {
        Text("_next_up;", color = PsPearl300.copy(alpha = 0.6f), fontFamily = FontMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(8.dp))

        Text(
            anchor?.let { "${it.title} — ${it.artist}" } ?: "select a track in the set",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("tempo", color = TextSecondary, fontSize = 11.sp, fontFamily = FontMono)
            Spacer(Modifier.width(6.dp))
            TOLERANCES.forEach { value ->
                val selected = value == tolerance
                Text(
                    "±${value.toInt()}%",
                    color = if (selected) PsInk900 else TextSecondary,
                    fontFamily = FontMono,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .background(if (selected) PsIrisCyan else PsInset)
                        .clickable { onTolerance(value) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            ToggleChip("harmonics", harmonicsOnly, onHarmonics)
            Spacer(Modifier.width(4.dp))
            ToggleChip("fold octave", foldTempo, onFold)
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onDetect,
                enabled = !detecting && anchor != null,
                shape = RoundedCornerShape(0.dp)
            ) {
                if (detecting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = PsIrisCyan, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("detect tempo + key", fontSize = 11.sp, fontFamily = FontMono)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (lookupConfigured) "analysed from the audio" else "no analyser available",
                color = if (lookupConfigured) PsSteel400 else PsSignalDanger,
                fontFamily = FontMono,
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        if (anchor == null) {
            Text(
                "nothing to mix from yet — search below to add the first track",
                color = TextSecondary,
                fontFamily = FontMono,
                fontSize = 12.sp
            )
        } else {
            Text("_in_set;", color = PsPearl300.copy(alpha = 0.6f), fontFamily = FontMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.height(6.dp))
            SuggestionList(
                ranking = inSet,
                emptyText = "no track in this set mixes with the anchor under these filters",
                action = null,
                onPlay = onPlay,
                onAnchor = onAnchor
            )
        }

        if (repertoire != null && (repertoire.suggestions.isNotEmpty() || repertoire.unranked.isNotEmpty())) {
            Spacer(Modifier.height(16.dp))
            Text("_repertoire;", color = PsPearl300.copy(alpha = 0.6f), fontFamily = FontMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.height(6.dp))
            SuggestionList(
                ranking = repertoire,
                emptyText = "nothing else in your sets mixes with this anchor",
                action = "+",
                onPlay = onPlay,
                onAnchor = onAnchor,
                onAdd = onAdd
            )
        }

        // Always rendered: an empty set has no anchor to rank from, and this is how it gets one.
        Spacer(Modifier.height(16.dp))
        Text("_discover;", color = PsPearl300.copy(alpha = 0.6f), fontFamily = FontMono, fontSize = 9.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = {
                    Text("_search_$providerLabel;", color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp)
                },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f).onKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyUp) { onSearch(); true } else false
                },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = TextPrimary,
                    cursorColor = TextPrimary,
                    focusedBorderColor = TextPrimary,
                    unfocusedBorderColor = PsPearl200,
                    backgroundColor = Surface,
                    placeholderColor = PsSteel400
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSearch, modifier = Modifier.size(44.dp).background(PsInk900)) {
                if (searching) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PsWhite, strokeWidth = 1.5.dp)
                else Icon(Icons.Default.Search, contentDescription = "Search", tint = PsWhite)
            }
        }

        Spacer(Modifier.height(8.dp))
        DiscoverList(
            discovery = discovered,
            emptyText = if (searching) "searching…" else "search for candidates to add",
            onPlay = onPlay,
            onAnchor = onAnchor,
            onAdd = onAdd
        )
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        label,
        color = if (on) PsInk900 else TextSecondary,
        fontFamily = FontMono,
        fontSize = 10.sp,
        modifier = Modifier
            .background(if (on) PsIrisCyan else PsInset)
            .clickable { onChange(!on) }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun SuggestionList(
    ranking: MixRanking?,
    emptyText: String,
    action: String?,
    onPlay: (MixTrack) -> Unit,
    onAnchor: (MixTrack) -> Unit,
    onAdd: ((MixTrack) -> Unit)? = null
) {
    val suggestions = ranking?.suggestions.orEmpty()
    if (suggestions.isEmpty()) {
        Text(emptyText, color = TextSecondary, fontFamily = FontMono, fontSize = 11.sp)
        if ((ranking?.unranked?.size ?: 0) > 0) {
            Text(
                "${ranking!!.unranked.size} track(s) left out: no matching key or tempo",
                color = PsSteel400,
                fontFamily = FontMono,
                fontSize = 10.sp
            )
        }
        return
    }

    LazyColumn(Modifier.heightIn(max = 220.dp)) {
        items(suggestions, key = { it.track.id }) { item ->
            TrackRow(
                track = item.track,
                affinity = item.affinity,
                action = action,
                onPlay = onPlay,
                onAnchor = onAnchor,
                onAdd = onAdd
            )
        }
    }
    if ((ranking?.unranked?.size ?: 0) > 0) {
        Text(
            "${ranking!!.unranked.size} track(s) left out: no matching key or tempo",
            color = PsSteel400,
            fontFamily = FontMono,
            fontSize = 10.sp
        )
    }
}

/**
 * The discover pane: the ranked matches first, then every candidate the engine could not score.
 * Each row can be added, because a track with no tempo or key yet is exactly the case a set has to
 * accept before it can be analysed.
 */
@Composable
private fun DiscoverList(
    discovery: Discovery,
    emptyText: String,
    onPlay: (MixTrack) -> Unit,
    onAnchor: (MixTrack) -> Unit,
    onAdd: (MixTrack) -> Unit
) {
    val rows = discovery.all
    if (rows.isEmpty()) {
        Text(emptyText, color = TextSecondary, fontFamily = FontMono, fontSize = 11.sp)
        return
    }

    LazyColumn(Modifier.heightIn(max = 260.dp)) {
        items(rows, key = { it.track.id }) { row ->
            TrackRow(
                track = row.track,
                affinity = row.affinity,
                action = "+",
                onPlay = onPlay,
                onAnchor = onAnchor,
                onAdd = onAdd
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: MixTrack,
    affinity: Affinity?,
    action: String?,
    onPlay: (MixTrack) -> Unit,
    onAnchor: (MixTrack) -> Unit,
    onAdd: ((MixTrack) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = HairlineSoft,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .clickable { onAnchor(track) }
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                affinity?.reasons?.joinToString(" · ")?.takeIf { it.isNotBlank() }
                    ?: "no tempo or key yet — detect it after adding",
                color = TextSecondary,
                fontFamily = FontMono,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            affinity?.score?.toString() ?: "—",
            color = if (affinity != null) PsInk900 else PsSteel400,
            fontFamily = FontMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(if (affinity != null) PsIrisCyan else PsInset)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
        IconButton(onClick = { onPlay(track) }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = PsSteel400, modifier = Modifier.size(16.dp))
        }
        if (action != null && onAdd != null) {
            IconButton(onClick = { onAdd(track) }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Add to set", tint = PsSteel400, modifier = Modifier.size(16.dp))
            }
        }
    }
}
