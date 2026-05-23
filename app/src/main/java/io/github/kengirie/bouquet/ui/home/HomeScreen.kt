package io.github.kengirie.bouquet.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kengirie.bouquet.BouquetApplication
import io.github.kengirie.bouquet.R
import io.github.kengirie.bouquet.viewer.ViewerActivity

/**
 * Thin Compose entry point for the home screen. All state and the resolve
 * coroutine live in [HomeViewModel]; this Composable renders, forwards
 * clicks, and listens for one-shot navigation events on
 * [HomeViewModel.events] so it can launch [ViewerActivity] when the
 * underlying nostr resolution succeeds.
 *
 * Visual design mirrors the bouquet-desktop home page: dark background with
 * a soft pink radial halo behind a large "Bouquet" headline, an uppercase
 * subtitle, a pill-shaped input row, action buttons, and a right-aligned
 * GitHub link in the footer.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Single collector keyed by the ViewModel instance: stays alive across
    // recomposition but stops if the VM is replaced. receiveAsFlow consumes
    // each event exactly once.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeViewModel.HomeEvent.LaunchViewer -> {
                    context.startActivity(
                        ViewerActivity.newIntent(context, event.addressSegment),
                    )
                }
                is HomeViewModel.HomeEvent.LaunchBrowser -> {
                    // Allocate a fresh NanoHTTPD listener for this browser
                    // tab and dispatch ACTION_VIEW pointing at its loopback
                    // port. No Activity owns this session, so opt into the
                    // registry's idle-timeout sweep (see Defaults).
                    val app = context.applicationContext as BouquetApplication
                    val session = app.sessionRegistry.createSession(
                        event.addressSegment,
                        idleTimeout = true,
                    )
                    val url = "http://127.0.0.1:${session.port}/"
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        // No browser installed — close the session we just
                        // allocated so we don't leak the listener.
                        app.sessionRegistry.closeSession(session.id)
                        Toast.makeText(
                            context,
                            context.getString(R.string.home_browser_not_found),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    val resolution = uiState.resolution
    val isResolving = resolution is ResolutionState.InProgress
    val canOpen = uiState.decodeResult is DecodeResult.Success && !isResolving

    // Approximate the desktop's `radial-gradient(circle at 50% 40%, …)` glow.
    // Using drawBehind lets us anchor the halo at the upper third of the box
    // and size the radius proportionally to the viewport so it scales across
    // phone form factors.
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawBehind {
                val brush = Brush.radialGradient(
                    colors = listOf(primaryGlow, Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.4f),
                    radius = size.width * 0.7f,
                )
                drawRect(brush = brush)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(48.dp))

            // Headline cluster ----------------------------------------------
            Text(
                text = stringResource(R.string.home_title).uppercase(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 72.sp,
                    lineHeight = 76.sp,
                    letterSpacing = (-2).sp,
                    shadow = Shadow(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        blurRadius = 30f,
                    ),
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Light,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(48.dp))

            // Pill-shaped input ---------------------------------------------
            OutlinedTextField(
                value = uiState.input,
                onValueChange = viewModel::onInputChange,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.home_input_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                leadingIcon = {
                    // Terminal-glyph approximation (mirrors desktop's
                    // material-symbol "terminal" leading icon). Using a
                    // Text glyph keeps APK lean by avoiding the
                    // material-icons-extended artifact.
                    Text(
                        text = ">_",
                        style = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 18.sp,
                    letterSpacing = (-0.3).sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Live decode panel + progress / error banner -------------------
            when (val r = uiState.decodeResult) {
                DecodeResult.Idle -> Unit
                is DecodeResult.Failure -> ErrorBanner(r.message)
                is DecodeResult.Success -> SuccessPanel(r.display)
            }

            when (resolution) {
                ResolutionState.Idle -> Unit
                is ResolutionState.InProgress -> {
                    Spacer(Modifier.height(16.dp))
                    InProgressRow(resolution.stage)
                }
                is ResolutionState.Failure -> {
                    Spacer(Modifier.height(16.dp))
                    ErrorBanner(resolution.message)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Open buttons --------------------------------------------------
            // Use primaryContainer for closer visual match to the desktop's
            // bg-primary-container "GO" button (#f75970).
            Button(
                onClick = { viewModel.onOpenClick(OpenTarget.Browser) },
                enabled = canOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.home_button_open_browser))
                Spacer(Modifier.width(8.dp))
                // Unicode rightwards arrow — substitutes for the desktop
                // material-symbol "arrow_forward" without pulling the
                // material-icons-extended artifact.
                Text(text = "→", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.onOpenClick(OpenTarget.WebView) },
                enabled = canOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(stringResource(R.string.home_button_open_webview))
                Spacer(Modifier.width(8.dp))
                Text(text = "→", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(48.dp))

            // Footer --------------------------------------------------------
            FooterRow()

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FooterRow() {
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.home_footer_github_url)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { uriHandler.openUri(githubUrl) }) {
            Text(
                text = stringResource(R.string.home_footer_github),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 4.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SuccessPanel(display: AddressDisplay) {
    val fields: List<Pair<String, String>> = remember(display) {
        buildList {
            add("Pubkey" to display.pubkey)
            display.identifier?.let { add("Identifier" to it.ifEmpty { "(empty)" }) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Type: ${display.typeLabel}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            fields.forEach { (label, value) ->
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InProgressRow(stage: ResolutionState.InProgress.Stage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(stageLabelRes(stage)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun stageLabelRes(stage: ResolutionState.InProgress.Stage): Int = when (stage) {
    ResolutionState.InProgress.Stage.FETCHING_WRITE_RELAYS -> R.string.home_status_fetching_relays
    ResolutionState.InProgress.Stage.FETCHING_MANIFEST -> R.string.home_status_fetching_manifest
    ResolutionState.InProgress.Stage.FETCHING_BLOSSOM_SERVERS -> R.string.home_status_fetching_blossom
}
