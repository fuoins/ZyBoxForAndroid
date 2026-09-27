package zy.hotspot.app.ui.apconfiguration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import zy.hotspot.app.R
import zy.hotspot.app.ui.DialogConfirmButton
import zy.hotspot.app.ui.DialogDismissButton
import zy.hotspot.app.ui.PreferenceRow
import zy.hotspot.app.ui.TooltipIconButton
import zy.hotspot.app.ui.annotatedStringResource
import zy.hotspot.app.ui.rememberDialogFocusRequester

@Composable
fun PasswordApRow(state: ApConfigurationState) {
    val context = LocalContext.current
    val maxLength = state.passwordMaxLength
    var editing by rememberSaveable(state.password) { mutableStateOf(false) }
    var draft by rememberSaveable(state.password, editing, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.password))
    }
    var visible by rememberSaveable(editing) { mutableStateOf(false) }
    val password = draft.text.toString()
    val error = state.passwordError(password, context)
    val title = stringResource(R.string.wifi_password)
    PreferenceRow(
        icon = R.drawable.ic_wifi_lock,
        title = title,
        summary = if (state.password.isEmpty()) "" else "\u2022".repeat(8),
        onClick = { editing = true },
    )
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(title) },
        text = {
            val focusRequester = rememberDialogFocusRequester()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = annotatedStringResource(R.string.wifi_password_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { newValue ->
                        draft = if (maxLength && newValue.text.length > 63) draft else newValue
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = title },
                    isError = error != null,
                    shape = OutlinedTextFieldDefaults.shape,
                    colors = OutlinedTextFieldDefaults.colors(),
                    supportingText = if (error != null || maxLength) {
                        {
                            Column {
                                error?.let { ErrorApText(it) }
                                if (maxLength) Text(stringResource(R.string.configuration_input_length, password.length, 63))
                            }
                        }
                    } else null,
                    trailingIcon = {
                        val tooltip = stringResource(
                            if (visible) R.string.wifi_password_hide else R.string.wifi_password_show,
                        )
                        TooltipIconButton(
                            tooltip = tooltip,
                            onClick = { visible = !visible },
                        ) {
                            Icon(
                                painter = painterResource(if (visible) {
                                    R.drawable.ic_visibility_off
                                } else R.drawable.ic_visibility),
                                contentDescription = tooltip,
                            )
                        }
                    },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (visible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            DialogConfirmButton(
                enabled = error == null,
                onClick = {
                    state.password = password
                    editing = false
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            DialogDismissButton(onClick = { editing = false }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
