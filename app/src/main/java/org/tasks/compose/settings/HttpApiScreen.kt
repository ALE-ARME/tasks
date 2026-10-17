package org.tasks.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.tasks.R
import org.tasks.themes.TasksIcons
import org.tasks.themes.WarningColor

/**
 * Settings for the local HTTP API: the switch starts and stops it, and the password and port are
 * what a client needs to talk to it. The endpoint is shown while it runs, so it can be copied into
 * a client without going back to the documentation.
 */
@Composable
fun HttpApiScreen(
    running: Boolean,
    port: Int,
    error: String?,
    password: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(SettingsContentPadding))

        Column(
            modifier = Modifier.padding(horizontal = SettingsContentPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
        ) {
            SettingsItemCard(position = CardPosition.Only) {
                SwitchPreferenceRow(
                    title = stringResource(R.string.http_api_enable),
                    summary = if (running) {
                        stringResource(R.string.http_api_status_running, port)
                    } else {
                        stringResource(R.string.http_api_status_stopped)
                    },
                    checked = running,
                    onCheckedChange = { enabled -> if (enabled) onStart() else onStop() },
                )
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(SettingsSectionGap))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = WarningColor,
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
            )
        }

        Spacer(modifier = Modifier.height(SettingsContentPadding))

        SectionHeader(
            R.string.http_api_access,
            modifier = Modifier.padding(horizontal = SettingsContentPadding),
        )
        Column(
            modifier = Modifier.padding(horizontal = SettingsContentPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
        ) {
            SettingsItemCard(position = CardPosition.First) {
                PreferenceRow(
                    title = stringResource(R.string.http_api_password),
                    summary = password.ifEmpty {
                        stringResource(R.string.http_api_password_unset)
                    },
                    icon = TasksIcons.LOCK,
                    showChevron = true,
                    onClick = { showPasswordDialog = true },
                )
            }
            SettingsItemCard(position = CardPosition.Last) {
                PreferenceRow(
                    title = stringResource(R.string.http_api_port),
                    summary = port.toString(),
                    icon = TasksIcons.TERMINAL,
                    showChevron = true,
                    onClick = { showPortDialog = true },
                )
            }
        }

        if (running) {
            Spacer(modifier = Modifier.height(SettingsContentPadding))
            Column(
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
            ) {
                SettingsItemCard(position = CardPosition.Only) {
                    PreferenceRow(
                        title = stringResource(R.string.http_api_endpoint),
                        summary = "http://127.0.0.1:$port/v0",
                        icon = TasksIcons.CLOUD,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(SettingsContentPadding))

        Text(
            text = stringResource(R.string.http_api_loopback),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsContentPadding),
        )
        Spacer(modifier = Modifier.height(SettingsSectionGap))
        Text(
            text = stringResource(R.string.http_api_intent_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsContentPadding),
        )

        Spacer(modifier = Modifier.height(SettingsContentPadding))
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    if (showPasswordDialog) {
        PasswordDialog(
            password = password,
            onDismiss = { showPasswordDialog = false },
            onConfirm = {
                onPasswordChange(it)
                showPasswordDialog = false
            },
        )
    }

    if (showPortDialog) {
        PortDialog(
            port = port,
            onDismiss = { showPortDialog = false },
            onConfirm = {
                onPortChange(it)
                showPortDialog = false
            },
        )
    }
}

@Composable
private fun PasswordDialog(
    password: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(password) { mutableStateOf(password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_api_password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsSectionGap)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.http_api_password)) },
                )
                Text(
                    text = stringResource(R.string.http_api_password_dialog_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PortDialog(
    port: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember(port) { mutableStateOf(port.toString()) }
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_api_port_dialog_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                isError = value.isNotEmpty() && !valid,
                label = { Text(stringResource(R.string.http_api_port)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
