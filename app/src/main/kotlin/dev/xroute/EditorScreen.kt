package dev.xroute

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.delay

internal val profileNames = listOf("Равномерно", "Разгон и торможение", "Волнами")

internal enum class EditorDialog { POINTS, DELETE, CLEAR, TARGET, PROFILE, SAVE, OVERWRITE, LIBRARY, DELETE_SAVE }

@Composable
internal fun EditorScreen(model: EditorModel, map: EditorMap, expanded: Boolean) {
    val palette = lightColorScheme(primary = Color(0xff006c62), secondary = Color(0xff41665c),
        surface = Color(0xfff8faf8), background = Color(0xfff8faf8), onSurface = Color(0xff172b25))
    var dialog by rememberSaveable { mutableStateOf<EditorDialog?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val state = model.state ?: return
    SyncMap(model, map, state)
    MaterialTheme(colorScheme = palette) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!expanded && !ime) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("XRoute", style = MaterialTheme.typography.headlineSmall)
                    Text(if (state.connected()) "Vector подключён" else "Vector отключён", style = MaterialTheme.typography.labelLarge)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                    val showMap = !ime || expanded
                    val mapWidth = if (wide && !expanded) maxWidth * .58f - 10.dp else maxWidth
                    val mapHeight = when {
                        expanded -> maxHeight
                        !showMap -> 0.dp
                        wide -> maxHeight
                        else -> maxHeight / 2.15f
                    }
                    Column(Modifier.size(mapWidth, mapHeight).clipToBounds(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AndroidView(factory = { map.view }, modifier = Modifier.fillMaxWidth().weight(1f))
                        if (showMap && model.preview) LinearProgressIndicator(
                            progress = { model.previewProgress }, modifier = Modifier.fillMaxWidth())
                        if (showMap) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { dialog = EditorDialog.POINTS }) {
                                Text(if (model.draft.selected < 0) "Точки" else "Точка ${model.draft.selected + 1} ▾")
                            }
                            TextButton(enabled = model.draft.selected >= 0, onClick = { dialog = EditorDialog.DELETE }) { Text("Удалить ▾") }
                        }
                    }
                    if (!expanded) {
                        val panel = if (wide && showMap) Modifier.width(maxWidth * .42f).fillMaxHeight()
                        else Modifier.fillMaxWidth().height(maxHeight - mapHeight)
                        EditorControls(model, state, panel.align(Alignment.BottomEnd)) { type ->
                            if (type == EditorDialog.SAVE) name = ""
                            dialog = type
                        }
                    }
                }
            }
        }
        dialog?.let { type ->
            EditorDialogs(type, model, map, state, name, { name = it }, { dialog = it })
        }
    }
}

@Composable
private fun EditorControls(model: EditorModel, state: PlaybackState, modifier: Modifier, show: (EditorDialog) -> Unit) {
    val draft = model.draft
    val ready = model.route != null && !model.dragging
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Целевое приложение", style = MaterialTheme.typography.titleMedium)
            if (state.targets().size > 1) OutlinedButton(onClick = { show(EditorDialog.TARGET) }) {
                Text(state.target().ifEmpty { "Выбрать приложение…" })
            } else if (state.target() in state.targets()) Text(state.target())
            if (state.scopeText().isNotEmpty()) Text(state.scopeText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft.speed, { model.edit(draft.copy(speed = it)) }, modifier = Modifier.weight(1f),
                    label = { Text("Скорость, км/ч") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedButton(onClick = { show(EditorDialog.PROFILE) }, modifier = Modifier.weight(1f)) {
                    Text(profileNames[draft.profile.ordinal])
                }
            }
            val kmh = draft.speed.trim().replace(',', '.').toDoubleOrNull()
            if (kmh == null || !(kmh > 0 && kmh <= 360)) Text("Введите скорость выше нуля, до 360 км/ч", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Скруглять повороты")
                Switch(draft.round, { model.edit(draft.copy(round = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::buildWalkingRoute, enabled = !model.routing && !model.dragging &&
                    draft.points.size in 2..WalkingDirections.MAX_WAYPOINTS) { Text(if (model.routing) "Строим…" else "По дорожкам") }
                Text("Координаты передаются в Valhalla (FOSSGIS)", style = MaterialTheme.typography.bodySmall)
            }
            if (draft.waypoints.isNotEmpty()) TextButton(onClick = model::restoreWaypoints) { Text("Вернуть исходные точки") }
            if (model.message.isNotEmpty()) Text(model.message)
            if (draft.points.size > WalkingDirections.MAX_WAYPOINTS) Text("Для построения по дорожкам доступно до ${WalkingDirections.MAX_WAYPOINTS} точек.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = ready, onClick = { show(EditorDialog.SAVE) }) { Text("Сохранить…") }
                OutlinedButton(onClick = { show(EditorDialog.LIBRARY) }) { Text("Сохранения") }
            }
        }
        HorizontalDivider()
        state.session()?.let { session ->
            val elapsed = (state.nowMs() - session.startMs()) / 1000.0
            val progress = (elapsed / session.route().durationSeconds()).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("playback-progress"))
        }
        Text(state.sessionText(), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(enabled = ready, onClick = {
                model.previewProgress = 0f
                model.preview = !model.preview
                if (model.preview) model.fitRequest++
            }) { Text(if (model.preview) "Скрыть просмотр" else "Предпросмотр") }
            Button(modifier = Modifier.testTag("playback-start"), enabled = ready && state.canStart(),
                onClick = { model.route?.let(model.controller::start) }) { Text(if (state.restart()) "Заново" else "Старт") }
            TextButton(modifier = Modifier.testTag("playback-stop"), enabled = state.canStop(),
                onClick = model.controller::stop) { Text("Стоп") }
        }
    }
}

@Composable
private fun SyncMap(model: EditorModel, map: EditorMap, state: PlaybackState) {
    val draft = model.draft
    val route = model.route
    LaunchedEffect(map, draft.points, draft.selected) {
        map.renderPoints(draft.points.map(Route.Point::geoPoint), draft.selected)
    }
    LaunchedEffect(map, draft.points, route) {
        map.renderLine((route?.geometry() ?: draft.points).map(Route.Point::geoPoint), route != null)
        val count = draft.points.size
        val word = when {
            count % 100 in 11..14 -> "точек"
            count % 10 == 1 -> "точка"
            count % 10 in 2..4 -> "точки"
            else -> "точек"
        }
        val summary = route?.let {
            val meters = it.durationSeconds() * it.meanMps
            val distance = if (meters < 1000) "%.0f м".format(meters) else "%.2f км".format(meters / 1000)
            " · $distance · ${duration(it.durationSeconds())}"
        }.orEmpty()
        map.caption("$count $word$summary")
    }
    LaunchedEffect(map, model.fitRequest) { if (model.fitRequest > 0) map.view.post { map.fit() } }
    LaunchedEffect(map, model.preview, state.receipt(), state.session()) {
        if (!model.preview) map.showPreview(state.receipt()?.fix(state.session()))
    }
    LaunchedEffect(map, model.preview, route, draft.selected) {
        if (model.preview && route != null) {
            val length = route.durationSeconds()
            val scale = maxOf(1.0, length / 24)
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = minOf(length, (android.os.SystemClock.elapsedRealtime() - start) * scale / 1000)
                model.previewProgress = (elapsed / length).toFloat().coerceIn(0f, 1f)
                val fix = route.sample(elapsed)
                map.showPreview(fix)
                map.hint(if (elapsed >= length) "Финиш предпросмотра · ${duration(length)}" else
                    "${duration(elapsed)} / ${duration(length)} · %.1f км/ч · ×%.1f".format(Locale.getDefault(), fix.speedMps() * 3.6, scale))
                if (elapsed >= length) break
                delay(33)
            }
        } else {
            map.hint(when {
                draft.points.size < 2 -> "Коснитесь карты, чтобы добавить точку"
                route == null -> runCatching { draft.buildRoute() }.exceptionOrNull()?.message.orEmpty()
                draft.points.size <= 5 -> "Удерживайте точку, чтобы перетащить"
                else -> ""
            })
        }
    }
}
