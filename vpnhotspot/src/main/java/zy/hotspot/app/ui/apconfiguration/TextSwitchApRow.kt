package zy.hotspot.app.ui.apconfiguration

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import zy.hotspot.app.R
import zy.hotspot.app.ui.DialogConfirmButton
import zy.hotspot.app.ui.DialogDismissButton
import zy.hotspot.app.ui.PreferenceRow
import zy.hotspot.app.ui.PreferenceSplitSwitch
import zy.hotspot.app.ui.PreferenceSwitch
import zy.hotspot.app.ui.nonInteractiveVerticalScrollbar
import zy.hotspot.app.ui.rememberDialogFocusRequester
import zy.hotspot.app.ui.rememberPreferenceSplitFocusModifiers

@Composable
fun TextSwitchApRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes valueTitle: Int,
    checked: Boolean,
    value: String,
    valueSummary: String = "",
    valueReadOnly: Boolean = false,
    summary: AnnotatedString? = null,
    description: AnnotatedString,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    maxLength: Int? = null,
    minLines: Int = if (value.contains('\n')) 3 else 1,
    placeholder: String? = null,
    suffix: String? = null,
    validator: (String) -> String?,
    onCheckedChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    var editing by rememberSaveable(value) { mutableStateOf(false) }
    var draft by rememberSaveable(value, editing, stateSaver = TextFieldValue.Saver) {
        mutableStateOf<TextFieldValue>(TextFieldValue(value))
    }
    val text = draft.text.toString()
    val error = validator(text)
    val titleText = stringResource(title)
    val (rowFocusModifier, switchFocusModifier) = rememberPreferenceSplitFocusModifiers()
    PreferenceRow(
        icon = icon,
        title = titleText,
        modifier = rowFocusModifier,
        summaryContent = if (summary == null && valueSummary.isEmpty()) null else {
            {
                Column {
                    summary?.let { Text(it) }
                    if (valueSummary.isNotEmpty()) Text(valueSummary)
                }
            }
        },
        trailing = {
            PreferenceSplitSwitch(
                label = titleText,
                checked = checked,
                modifier = switchFocusModifier,
                onCheckedChange = onCheckedChange,
            )
        },
        onClick = { editing = true },
    )
    if (editing) {
        val fieldEnabled = !valueReadOnly
        AlertDialog(
            onDismissRequest = { editing = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = titleText,
                        modifier = Modifier.weight(1f),
                    )
                    PreferenceSwitch(
                        checked = checked,
                        modifier = Modifier.semantics { contentDescription = titleText },
                        onCheckedChange = onCheckedChange,
                    )
                }
            },
            text = {
                val focusRequester = rememberDialogFocusRequester(fieldEnabled)
                val multiline = minLines > 1
                val scrollState = rememberScrollState()
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { newValue ->
                            draft = if (maxLength != null && newValue.text.length > maxLength) draft else newValue
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        enabled = fieldEnabled,
                        label = { Text(stringResource(valueTitle)) },
                        keyboardOptions = keyboardOptions,
                        placeholder = placeholder?.let { { Text(it) } },
                        minLines = if (multiline) minLines else 1,
                        maxLines = if (multiline) minLines else 1,
                        isError = fieldEnabled && error != null,
                        shape = OutlinedTextFieldDefaults.shape,
                        colors = OutlinedTextFieldDefaults.colors(),
                        suffix = suffix?.let { { Text(it) } },
                        supportingText = if ((fieldEnabled && error != null) || maxLength != null) {
                            {
                                Column {
                                    if (fieldEnabled) error?.let { ErrorApText(it) }
                                    maxLength?.let { Text(stringResource(R.string.configuration_input_length, text.length, it)) }
                                }
                            }
                        } else null,
                    )
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    enabled = !fieldEnabled || error == null,
                    onClick = {
                        if (fieldEnabled) onValueChange(text)
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
}
