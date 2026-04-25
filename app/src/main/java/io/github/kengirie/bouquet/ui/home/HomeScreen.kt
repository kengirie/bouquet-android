package io.github.kengirie.bouquet.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kengirie.bouquet.R
import io.github.kengirie.bouquet.viewer.ViewerActivity

/**
 * Thin Compose entry point for the home screen. All state and the resolve
 * coroutine live in [HomeViewModel]; this Composable renders, forwards
 * clicks, and listens for one-shot navigation events on
 * [HomeViewModel.events] so it can launch [ViewerActivity] when the
 * underlying nostr resolution succeeds.
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
            }
        }
    }

    val resolution = uiState.resolution
    val isResolving = resolution is ResolutionState.InProgress
    val canOpen = uiState.decodeResult is DecodeResult.Success && !isResolving

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = uiState.input,
            onValueChange = viewModel::onInputChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.home_input_label)) },
            placeholder = { Text(stringResource(R.string.home_input_placeholder)) },
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::onOpenClick,
            enabled = canOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_button_open))
        }

        Spacer(Modifier.height(24.dp))

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
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
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
            display.kind?.let { add("Kind" to it.toString()) }
            if (display.relayHints.isNotEmpty()) {
                add("Relay hints" to display.relayHints.joinToString(", "))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
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
            )
            fields.forEach { (label, value) ->
                Column {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
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
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(stageLabelRes(stage)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun stageLabelRes(stage: ResolutionState.InProgress.Stage): Int = when (stage) {
    ResolutionState.InProgress.Stage.FETCHING_WRITE_RELAYS -> R.string.home_status_fetching_relays
    ResolutionState.InProgress.Stage.FETCHING_MANIFEST -> R.string.home_status_fetching_manifest
    ResolutionState.InProgress.Stage.FETCHING_BLOSSOM_SERVERS -> R.string.home_status_fetching_blossom
}
