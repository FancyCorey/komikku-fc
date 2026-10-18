package exh.recs.matching

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.presentation.core.components.material.padding

@Composable
internal fun ConfirmedGroupRatingControl(
    loading: Boolean,
    updating: Boolean,
    buttonLabel: String,
    notRatedLabel: String,
    ratingOptions: List<Pair<MangaRating, String>>,
    onSetRating: (MangaRating?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !loading && !updating,
            modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.padding.small),
        ) {
            Text(buttonLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(notRatedLabel) },
                onClick = {
                    expanded = false
                    onSetRating(null)
                },
            )
            ratingOptions.forEach { (rating, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSetRating(rating)
                    },
                )
            }
        }
    }
}
