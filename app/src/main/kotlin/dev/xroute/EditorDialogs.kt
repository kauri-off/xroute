package dev.xroute

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun EditorDialogs(
    type: EditorDialog, model: EditorModel, map: EditorMap, state: PlaybackState,
    name: String, setName: (String) -> Unit, show: (EditorDialog?) -> Unit
) {
    val draft = model.draft
    val close = { show(null) }
    val title = when (type) {
        EditorDialog.POINTS -> "Точки маршрута"
        EditorDialog.DELETE -> "Удаление точек"
        EditorDialog.CLEAR -> "Удалить все точки из текущего маршрута?"
        EditorDialog.TARGET -> "Приложения из Vector"
        EditorDialog.PROFILE -> "Профиль скорости"
        EditorDialog.SAVE -> "Сохранить маршрут"
        EditorDialog.OVERWRITE -> "Заменить маршрут «${name.trim()}»?"
        EditorDialog.LIBRARY -> "Сохранённые маршруты"
        EditorDialog.DELETE_SAVE -> "Удалить сохранение «$name»?"
    }
    val dismiss = { if (type == EditorDialog.DELETE_SAVE) show(EditorDialog.LIBRARY) else close() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        when (type) {
            EditorDialog.POINTS -> Column {
                val delivered = state.receipt()?.fix(state.session())
                if (delivered != null) TextButton(onClick = { map.center(delivered.point().geoPoint()); close() }) {
                    Text("К точке воспроизведения")
                }
                if (draft.points.isNotEmpty()) TextButton(onClick = { map.fit(); close() }) { Text("Весь маршрут") }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(draft.points) { index, point ->
                        Choice("%d     %.6f, %.6f".format(Locale.ROOT,index + 1, point.lat(), point.lon())) {
                            model.select(index)
                            map.center(point.geoPoint())
                            close()
                        }
                    }
                }
            }
            EditorDialog.DELETE -> Column {
                Choice("Удалить точку ${draft.selected + 1}") { model.delete(false); close() }
                Choice("Удалить все точки…") { show(EditorDialog.CLEAR) }
            }
            EditorDialog.SAVE -> OutlinedTextField(name, setName, singleLine = true, label = { Text("Название маршрута") })
            EditorDialog.LIBRARY -> {
                if (model.names.isEmpty()) Text("Нет сохранённых маршрутов")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(model.names.size, key = { model.names[it] }) { index ->
                        val title = model.names[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).clickable { model.open(title); close() }.padding(vertical = 12.dp))
                            TextButton(onClick = { setName(title); show(EditorDialog.DELETE_SAVE) }) { Text("Удалить") }
                        }
                    }
                }
            }
            EditorDialog.PROFILE, EditorDialog.TARGET -> {
                val options = when (type) {
                    EditorDialog.PROFILE -> profileNames
                    else -> state.targets()
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(options) { index, value ->
                        Choice(value) {
                            when (type) {
                                EditorDialog.PROFILE -> model.edit(draft.copy(profile = Route.Profile.entries[index]))
                                else -> model.controller.selectTarget(value)
                            }
                            close()
                        }
                    }
                }
            }
            else -> Unit
        }
    }, confirmButton = {
        when (type) {
            EditorDialog.DELETE_SAVE -> Button(onClick = { model.deleteSave(name); show(EditorDialog.LIBRARY) }) { Text("Удалить") }
            EditorDialog.CLEAR -> Button(onClick = { model.delete(true); close() }) { Text("Удалить все") }
            EditorDialog.SAVE, EditorDialog.OVERWRITE -> Button(enabled = name.isNotBlank(), onClick = {
                if (type == EditorDialog.SAVE && name.trim() in model.names) show(EditorDialog.OVERWRITE)
                else { model.save(name.trim()); close() }
            }) { Text(if (type == EditorDialog.OVERWRITE) "Заменить" else "Сохранить") }
            else -> Unit
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(if (type == EditorDialog.DELETE_SAVE) "Отмена" else "Закрыть") } })
}

@Composable
private fun Choice(text: String, choose: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = choose).padding(vertical = 12.dp))
}
