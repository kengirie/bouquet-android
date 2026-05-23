package io.github.kengirie.bouquet.ui.timeline

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.kengirie.bouquet.BouquetApplication
import io.github.kengirie.bouquet.R
import io.github.kengirie.bouquet.ui.home.OpenTarget
import io.github.kengirie.bouquet.viewer.ViewerActivity

/**
 * Timeline tab: discovery feed of nsite manifests aggregated across the
 * default discovery relays.
 *
 * Tapping a card opens a modal bottom sheet that lets the user pick between
 * the in-app WebView ([ViewerActivity]) and the system browser (dispatched
 * via a freshly allocated NanoHTTPD session, mirroring the Home screen's
 * "Open in browser" button). The resolve work runs through the same pipeline
 * as Home so both surfaces share caching and error semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(modifier: Modifier = Modifier) {
    val viewModel: TimelineViewModel = viewModel(factory = TimelineViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingItem by remember { mutableStateOf<TimelineItem?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val sheetScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TimelineViewModel.TimelineEvent.LaunchViewer -> {
                    context.startActivity(
                        ViewerActivity.newIntent(context, event.addressSegment),
                    )
                }
                is TimelineViewModel.TimelineEvent.LaunchBrowser -> {
                    val app = context.applicationContext as BouquetApplication
                    val session = app.sessionRegistry.createSession(event.addressSegment)
                    val url = "http://127.0.0.1:${session.port}/"
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        app.sessionRegistry.closeSession(session.id)
                        Toast.makeText(
                            context,
                            context.getString(R.string.home_browser_not_found),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                is TimelineViewModel.TimelineEvent.ResolveFailed -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.timeline_resolve_failed, event.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.timeline_title).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Light,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Text(
                        text = "↻",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        when (val s = state) {
            TimelineUiState.Loading -> CenteredProgress()
            TimelineUiState.Empty ->
                CenteredText(stringResource(R.string.timeline_empty))
            is TimelineUiState.Error ->
                ErrorPanel(message = s.message)
            is TimelineUiState.Loaded -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = s.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = s.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                ) {
                    TimelineList(
                        items = s.items,
                        resolvingEventId = s.resolvingEventId,
                        onTap = { pendingItem = it },
                    )
                }
            }
        }
    }

    pendingItem?.let { item ->
        OpenTargetSheet(
            sheetState = sheetState,
            onDismiss = { pendingItem = null },
            onSelect = { target ->
                viewModel.onItemClick(item, target)
                // Hide with animation, then clear the pending item once
                // the sheet has fully disappeared so the dismiss feels
                // continuous rather than snapping away.
                sheetScope.launch {
                    sheetState.hide()
                    pendingItem = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenTargetSheet(
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onSelect: (OpenTarget) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.timeline_open_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            OpenTargetRow(
                glyph = "🌐",
                label = stringResource(R.string.home_button_open_browser),
                onClick = { onSelect(OpenTarget.Browser) },
            )
            OpenTargetRow(
                glyph = "📱",
                label = stringResource(R.string.home_button_open_webview),
                onClick = { onSelect(OpenTarget.WebView) },
            )
        }
    }
}

@Composable
private fun OpenTargetRow(glyph: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TimelineList(
    items: List<TimelineItem>,
    resolvingEventId: String?,
    onTap: (TimelineItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = items, key = { it.entry.eventId }) { item ->
            TimelineCard(
                item = item,
                isResolving = item.entry.eventId == resolvingEventId,
                onTap = { onTap(item) },
            )
        }
    }
}

@Composable
private fun TimelineCard(
    item: TimelineItem,
    isResolving: Boolean,
    onTap: () -> Unit,
) {
    val entry = item.entry
    val author = item.author
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Block clicks while resolving so back-to-back taps on the
            // same card don't queue work that's already in flight.
            .clickable(enabled = !isResolving, onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Avatar(
                    pictureUrl = author?.pictureUrl,
                    fallbackSeed = author?.displayName ?: entry.pubkey,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = author?.displayName?.takeIf { it.isNotBlank() }
                                ?: shortPubkey(entry.pubkey),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatRelativeTime(entry.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val typeLine = when (entry) {
                        is TimelineEntry.Named -> entry.identifier.ifBlank {
                            stringResource(R.string.timeline_named_default)
                        }
                        is TimelineEntry.Root ->
                            stringResource(R.string.timeline_root_label)
                    }
                    Text(
                        text = typeLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (isResolving) {
                // Translucent overlay + spinner over the entire card so
                // it's obvious which card is being acted on.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(pictureUrl: String?, fallbackSeed: String) {
    val context = LocalContext.current
    val size = 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
        } else {
            Text(
                text = avatarInitial(fallbackSeed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun avatarInitial(seed: String): String =
    seed.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

private fun shortPubkey(pubkey: String): String =
    if (pubkey.length <= 12) pubkey
    else pubkey.take(6) + "…" + pubkey.takeLast(4)

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.timeline_error_prefix),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}
