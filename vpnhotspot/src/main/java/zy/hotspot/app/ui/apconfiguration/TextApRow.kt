package zy.hotspot.app.ui.apconfiguration

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import zy.hotspot.app.ui.nonInteractiveVerticalScrollbar
import zy.hotspot.app.ui.rememberDialogFocusRequester

@Composable
fun TextApRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    value: String,
    description: AnnotatedString,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    maxLength: Int? = null,
    minLines: Int = if (value.contains('\n')) 3 else 1,
    validator: (String) -> String?,
    onValueChange: (String) -> Unit,
) {
    var editing by rememberSaveable(value) { mutableStateOf(false) }
    var draft by rememberSaveable(value, editing, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value))
    }
    val text = draft.text.toString()
    val error = validator(text)
    val titleText = stringResource(title)
    PreferenceRow(
        icon = icon,
        title = titleText,
        summary = value,
        onClick = { editing = true },
    )
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(titleText) },
        text = {
            val focusRequester = rememberDialogFocusRequester()
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
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = titleText },
                    keyboardOptions = keyboardOptions,
                    minLines = if (multiline) minLines else 1,
                    maxLines = if (multiline) minLines else 1,
                    isError = error != null,
                    shape = OutlinedTextFieldDefaults.shape,
                    colors = OutlinedTextFieldDefaults.colors(),
                    supportingText = if (error != null || maxLength != null) {
                        {
                            Column {
                                error?.let { ErrorApText(it) }
                                maxLength?.let { Text(stringResource(R.string.configuration_input_length, text.length, it)) }
                            }
                        }
                    } else null,
                )
            }
        },
        confirmButton = {
            DialogConfirmButton(
                enabled = error == null,
                onClick = {
                    onValueChange(text)
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
