package com.aiprojects.tankbattle.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiprojects.tankbattle.audio.TankBattleSound
import com.aiprojects.tankbattle.level.LevelAssetLoader
import com.aiprojects.tankbattle.game.Direction
import com.aiprojects.tankbattle.game.EnemyTankType
import com.aiprojects.tankbattle.game.ExplosionFx
import com.aiprojects.tankbattle.game.ExplosionKind
import com.aiprojects.tankbattle.game.GameEngine
import com.aiprojects.tankbattle.game.GameSnapshot
import com.aiprojects.tankbattle.game.PickupKind
import com.aiprojects.tankbattle.game.Phase
import com.aiprojects.tankbattle.game.Tile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val ControlPadSize = 100.dp
private val JoyKnobSize = 38.dp
private val ControlMoveHandleH = 22.dp
private val JoyColumnExtra = 4.dp
private val DpadBtnSize = 44.dp
private val DpadGap = 6.dp
private val FireBtnH = 72.dp
private val FireBtnMinW = 100.dp

private enum class ControlInputMode {
    Joystick,
    Buttons,
}

private val ControlInputModeSaver: Saver<ControlInputMode, Boolean> =
    Saver(
        save = { mode -> mode == ControlInputMode.Buttons },
        restore = { stored ->
            if (stored) ControlInputMode.Buttons else ControlInputMode.Joystick
        },
    )

/** px → 与 [androidx.compose.ui.unit.Dp] 一致的 float 存储值（与 Dp.toPx() 互逆） */
private fun Density.pixelsToDpValue(px: Float): Float =
    px / (density * fontScale)

private fun leftControlBodyWidthPx(mode: ControlInputMode, density: Density): Float =
    with(density) {
        when (mode) {
            ControlInputMode.Joystick -> ControlPadSize.toPx()
            ControlInputMode.Buttons ->
                DpadBtnSize.toPx() * 3f + DpadGap.toPx() * 2f
        }
    }

private fun leftControlBodyHeightPx(mode: ControlInputMode, density: Density): Float =
    with(density) {
        when (mode) {
            ControlInputMode.Joystick -> ControlPadSize.toPx()
            ControlInputMode.Buttons ->
                DpadBtnSize.toPx() * 3f + DpadGap.toPx() * 2f
        }
    }

private fun clampLeftControlOffset(
    nxDp: Float,
    nyDp: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    density: Density,
    mode: ControlInputMode,
): Pair<Float, Float> {
    val margin = with(density) { 8.dp.toPx() }
    val startPad = with(density) { 14.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }
    val joyW = leftControlBodyWidthPx(mode, density)
    val joyH =
        with(density) {
            ControlMoveHandleH.toPx() + JoyColumnExtra.toPx() + leftControlBodyHeightPx(mode, density)
        }
    var ox = with(density) { nxDp.dp.toPx() }
    var oy = with(density) { nyDp.dp.toPx() }
    val minOx = margin - startPad
    val maxOx = maxWidthPx - margin - joyW - startPad
    ox = ox.coerceIn(minOf(minOx, maxOx), maxOf(minOx, maxOx))
    val minOy = margin - maxHeightPx + bottomPad + joyH
    val maxOy = bottomPad - margin
    val yLow = minOf(minOy, maxOy)
    val yHigh = maxOf(minOy, maxOy)
    oy = oy.coerceIn(yLow, yHigh)
    return density.pixelsToDpValue(ox) to density.pixelsToDpValue(oy)
}

private fun clampFireOffset(
    nxDp: Float,
    nyDp: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    density: Density,
): Pair<Float, Float> {
    val margin = with(density) { 8.dp.toPx() }
    val endPad = with(density) { 44.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }
    val fireW = with(density) { FireBtnMinW.toPx() }
    val fireH = with(density) { (FireBtnH + ControlMoveHandleH + JoyColumnExtra).toPx() }
    var ox = with(density) { nxDp.dp.toPx() }
    var oy = with(density) { nyDp.dp.toPx() }
    val defaultLeft = maxWidthPx - endPad - fireW
    val minFx = margin - defaultLeft
    val maxFx = maxWidthPx - margin - fireW - defaultLeft
    ox = ox.coerceIn(minOf(minFx, maxFx), maxOf(minFx, maxFx))
    val minOy = margin - maxHeightPx + bottomPad + fireH
    val maxOy = bottomPad - margin
    val yLow = minOf(minOy, maxOy)
    val yHigh = maxOf(minOy, maxOy)
    oy = oy.coerceIn(yLow, yHigh)
    return density.pixelsToDpValue(ox) to density.pixelsToDpValue(oy)
}


@Composable
fun TankBattleRoot() {
    val context = LocalContext.current
    val campaign = remember { LevelAssetLoader.loadCampaign(context) }
    val engine = remember(campaign) { GameEngine(campaign) }
    val snap by engine.snapshot
    val sound = remember { TankBattleSound(context) }

    DisposableEffect(engine, sound) {
        engine.onSfx = { sound.play(it) }
        onDispose {
            engine.onSfx = null
            sound.release()
        }
    }

    LaunchedEffect(engine) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameTime ->
                val dt = ((frameTime - last) / 1_000_000_000f).coerceIn(1f / 240f, 1f / 30f)
                last = frameTime
                engine.update(dt)
            }
        }
    }

    TankBattleScreen(
        snap = snap,
        onSteer = { engine.playerSteer = it },
        onFire = { engine.playerWantsFire = true },
        onRestart = { engine.restart() },
    )
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun TankBattleScreen(
    snap: GameSnapshot,
    onSteer: (Direction?) -> Unit,
    onFire: () -> Unit,
    onRestart: () -> Unit,
) {
    val cols = snap.cols.toFloat()
    val rows = snap.rows.toFloat()
    val playing = snap.phase == Phase.Playing

    var controlMode by rememberSaveable(stateSaver = ControlInputModeSaver) {
        mutableStateOf(ControlInputMode.Joystick)
    }

    LaunchedEffect(controlMode) {
        onSteer(null)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080808))
            // safeDrawing：状态栏、导航栏、刘海/挖孔等并集后的安全绘制区；clip 防止控件画出边界
            .safeDrawingPadding()
            .clip(RectangleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF050505)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // contain：取较小缩放比，整张地图始终在画布内居中，四周可留窄边（不裁切格子）
                val scaleW = size.width / cols
                val scaleH = size.height / rows
                val tile = min(scaleW, scaleH)
                val ox = (size.width - tile * cols) / 2f
                val oy = (size.height - tile * rows) / 2f

                fun wx(x: Float) = ox + x * tile
                fun wy(y: Float) = oy + y * tile

                val brick = Color(0xFFB5651D)
                val steel = Color(0xFF9AA0A6)
                val base = Color(0xFFFFC107)
                val grass = Color(0xFF1B5E20)

                for (y in 0 until snap.rows) {
                    for (x in 0 until snap.cols) {
                        val t = snap.tiles[y][x]
                        val left = wx(x.toFloat())
                        val top = wy(y.toFloat())
                        val w = tile
                        val h = tile
                        when (t) {
                            Tile.Empty -> drawRect(grass, Offset(left, top), Size(w, h))
                            Tile.Brick -> drawRect(brick, Offset(left, top), Size(w, h))
                            Tile.Steel -> drawRect(steel, Offset(left, top), Size(w, h))
                            Tile.Base -> drawRect(base, Offset(left, top), Size(w, h))
                        }
                    }
                }

                val p = snap.player
                if (p.alive) {
                    val tcx = wx(p.x)
                    val tcy = wy(p.y)
                    drawTankHealthBar(tcx, tcy, tile, p.hp, p.maxHp, Color(0xFF8BC34A))
                    drawTank(tcx, tcy, tile, Color(0xFFFFEB3B), p.facing)
                }

                for (e in snap.enemies) {
                    if (!e.alive) continue
                    val ecx = wx(e.x)
                    val ecy = wy(e.y)
                    drawTankHealthBar(ecx, ecy, tile, e.hp, e.maxHp, Color(0xFFE57373))
                    drawTank(ecx, ecy, tile, enemyBodyColor(e.enemyType), e.facing)
                }

                for (pk in snap.pickups) {
                    drawPickup(wx(pk.x), wy(pk.y), tile, pk.kind)
                }

                val bullet = Color(0xFFFFFFFF)
                for (b in snap.bullets) {
                    val cx = wx(b.x)
                    val cy = wy(b.y)
                    val r = tile * 0.08f
                    drawCircle(bullet, r, Offset(cx, cy))
                }

                for (ex in snap.explosions) {
                    drawExplosionFx(
                        center = Offset(wx(ex.x), wy(ex.y)),
                        tile = tile,
                        fx = ex,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x59181818))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "得分 ${snap.score} · 火力×${snap.player.attack}",
                    color = Color(0xFFF5F5F5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "生命 ${snap.lives}",
                    color = Color(0xFFF5F5F5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                val left = snap.enemiesRemaining + snap.enemies.count { it.alive }
                Text(
                    text = "敌军 $left",
                    color = Color(0xFFF5F5F5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "第 ${snap.levelDisplayIndex}/${snap.totalLevels} 关 · ${snap.levelTitle}",
                color = Color(0xE8ECEFF1),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x40202020))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "操控",
                    color = Color(0xFF909090),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(10.dp))
                ControlSchemeSegmented(
                    mode = controlMode,
                    onModeChange = { controlMode = it },
                )
            }
        }

        if (playing) {
            var joyNX by rememberSaveable { mutableStateOf(0f) }
            var joyNY by rememberSaveable { mutableStateOf(0f) }
            var fireNX by rememberSaveable { mutableStateOf(0f) }
            var fireNY by rememberSaveable { mutableStateOf(0f) }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f),
            ) {
                val density = LocalDensity.current
                val maxWp = constraints.maxWidth.toFloat()
                val maxHp = constraints.maxHeight.toFloat()

                LaunchedEffect(maxWp, maxHp, density, controlMode) {
                    val j = clampLeftControlOffset(joyNX, joyNY, maxWp, maxHp, density, controlMode)
                    joyNX = j.first
                    joyNY = j.second
                    val f = clampFireOffset(fireNX, fireNY, maxWp, maxHp, density)
                    fireNX = f.first
                    fireNY = f.second
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 18.dp)
                        .offset {
                            IntOffset(
                                with(density) { joyNX.dp.roundToPx() },
                                with(density) { joyNY.dp.roundToPx() },
                            )
                        },
                ) {
                    ControlMoveHandle(
                        label = when (controlMode) {
                            ControlInputMode.Joystick -> "摇杆位置"
                            ControlInputMode.Buttons -> "按键位置"
                        },
                        onDrag = { dx, dy ->
                            val j = clampLeftControlOffset(
                                joyNX + dx,
                                joyNY + dy,
                                maxWp,
                                maxHp,
                                density,
                                controlMode,
                            )
                            joyNX = j.first
                            joyNY = j.second
                        },
                    )
                    Spacer(Modifier.height(JoyColumnExtra))
                    when (controlMode) {
                        ControlInputMode.Joystick -> DirectionPad(onSteer = onSteer)
                        ControlInputMode.Buttons -> DirectionButtonPad(onSteer = onSteer)
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 44.dp, bottom = 18.dp)
                        .offset {
                            IntOffset(
                                with(density) { fireNX.dp.roundToPx() },
                                with(density) { fireNY.dp.roundToPx() },
                            )
                        },
                ) {
                    ControlMoveHandle(
                        label = "开火位置",
                        onDrag = { dx, dy ->
                            val f = clampFireOffset(fireNX + dx, fireNY + dy, maxWp, maxHp, density)
                            fireNX = f.first
                            fireNY = f.second
                        },
                    )
                    Spacer(Modifier.height(JoyColumnExtra))
                    FloatingFireButton(onClick = onFire)
                }
            }
        }

        if (snap.phase != Phase.Playing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (snap.phase) {
                            Phase.Won ->
                                if (snap.totalLevels > 1) "通关！" else "胜利"

                            Phase.Lost -> "失败"
                            Phase.Playing -> ""
                        },
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRestart) {
                        Text("再来一局")
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlSchemeSegmented(
    mode: ControlInputMode,
    onModeChange: (ControlInputMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x402A2A2A))
            .padding(3.dp),
    ) {
        val joyOn = mode == ControlInputMode.Joystick
        val btnOn = mode == ControlInputMode.Buttons
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (joyOn) Color(0xFF455A64) else Color.Transparent)
                .clickable { onModeChange(ControlInputMode.Joystick) }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "摇杆",
                fontSize = 12.sp,
                fontWeight = if (joyOn) FontWeight.SemiBold else FontWeight.Normal,
                color = if (joyOn) Color.White else Color(0xFFAAAAAA),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (btnOn) Color(0xFF455A64) else Color.Transparent)
                .clickable { onModeChange(ControlInputMode.Buttons) }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "按键",
                fontSize = 12.sp,
                fontWeight = if (btnOn) FontWeight.SemiBold else FontWeight.Normal,
                color = if (btnOn) Color.White else Color(0xFFAAAAAA),
            )
        }
    }
}

@Composable
private fun ControlMoveHandle(
    label: String,
    onDrag: (dxDp: Float, dyDp: Float) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .height(ControlMoveHandleH)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x661E1E1E))
            .pointerInput(density) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(
                            dragAmount.x / density.density,
                            dragAmount.y / density.density,
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xCCD0D0D0),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun DirectionButtonPad(onSteer: (Direction?) -> Unit) {
    val steerCb by rememberUpdatedState(onSteer)
    val pressStack = remember { ArrayDeque<Direction>() }

    fun handleDown(d: Direction) {
        pressStack.remove(d)
        pressStack.addLast(d)
        steerCb(pressStack.last())
    }

    fun handleUp(d: Direction) {
        pressStack.remove(d)
        steerCb(pressStack.lastOrNull())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DpadGap)
    ) {
        DpadArrowButton("↑") { handleDown(Direction.Up) } { handleUp(Direction.Up) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(DpadGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DpadArrowButton("←") { handleDown(Direction.Left) } { handleUp(Direction.Left) }
            // 中间空白按钮（可选）
            Box(
                modifier = Modifier
                    .size(DpadBtnSize)
                    .background(Color.Transparent)
            )
            DpadArrowButton("→") { handleDown(Direction.Right) } { handleUp(Direction.Right) }
        }
        DpadArrowButton("↓") { handleDown(Direction.Down) } { handleUp(Direction.Down) }
    }
}

@Composable
private fun DpadArrowButton(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val downCb by rememberUpdatedState(onDown)
    val upCb by rememberUpdatedState(onUp)
    Box(
        modifier = Modifier
            .size(DpadBtnSize)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x42000000))
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val ev = awaitFirstDown()
                    downCb()
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.find { it.id == ev.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        upCb()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DirectionPad(
    modifier: Modifier = Modifier,
    onSteer: (Direction?) -> Unit,
) {
    val steerCb by rememberUpdatedState(onSteer)
    val knobOffset = remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val minDeadPx = remember(density) { with(density) { 2.5.dp.toPx() } }

    Box(
        modifier = modifier
            .size(ControlPadSize)
            .clip(CircleShape)
            .background(Color(0x42000000))
            .border(1.dp, Color(0x44FFFFFF), CircleShape)
            .pointerInput(minDeadPx) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = min(size.width, size.height) * 0.44f
                val deadR = max(maxR * 0.055f, minDeadPx)

                fun dirFromKnob(rx: Float, ry: Float): Direction? {
                    val len = hypot(rx.toDouble(), ry.toDouble()).toFloat()
                    if (len < deadR) return null
                    return if (abs(rx) >= abs(ry)) {
                        if (rx < 0f) Direction.Left else Direction.Right
                    } else {
                        if (ry < 0f) Direction.Up else Direction.Down
                    }
                }

                fun applyTouch(pos: Offset) {
                    val rx = pos.x - cx
                    val ry = pos.y - cy
                    var vx = rx
                    var vy = ry
                    val len = hypot(vx.toDouble(), vy.toDouble()).toFloat()
                    if (len > maxR && len > 0f) {
                        vx *= maxR / len
                        vy *= maxR / len
                    }
                    knobOffset.value = Offset(vx, vy)
                    steerCb(dirFromKnob(rx, ry))
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    applyTouch(down.position)

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change =
                            event.changes.find { it.id == down.id } ?: break
                        change.consume()
                        applyTouch(change.position)
                        if (!change.pressed) break
                    }

                    knobOffset.value = Offset.Zero
                    steerCb(null)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val o = knobOffset.value
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                .size(JoyKnobSize)
                .clip(CircleShape)
                .background(Color(0x7AFFFFFF))
                .border(1.dp, Color(0x99FFFFFF), CircleShape),
        )
    }
}

@Composable
private fun FloatingFireButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .widthIn(min = 100.dp)
            .height(72.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x59FF7043),
            contentColor = Color.White,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 4.dp  // 按压时升高，提供视觉反馈
        )
    ) {
        Text("开火", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun enemyBodyColor(type: EnemyTankType?): Color =
    when (type) {
        null -> Color(0xFFE53935)
        EnemyTankType.Light -> Color(0xFF8BC34A)
        EnemyTankType.Normal -> Color(0xFFE57373)
        EnemyTankType.Heavy -> Color(0xFF5E35B1)
        EnemyTankType.Assault -> Color(0xFFFF8F00)
        EnemyTankType.Elite -> Color(0xFFB71C1C)
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTankHealthBar(
    centerX: Float,
    centerY: Float,
    tile: Float,
    hp: Int,
    maxHp: Int,
    fill: Color,
) {
    val w = tile * 0.56f
    val h = max(tile * 0.07f, 2f)
    val left = centerX - w / 2f
    val top = centerY - tile * 0.52f
    val ratio =
        if (maxHp <= 0) {
            0f
        } else {
            (hp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)
        }
    drawRect(Color(0xCC000000), Offset(left, top), Size(w, h))
    drawRect(fill, Offset(left, top), Size(w * ratio, h))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPickup(
    centerX: Float,
    centerY: Float,
    tile: Float,
    kind: PickupKind,
) {
    val r = tile * 0.11f
    when (kind) {
        PickupKind.Repair -> {
            drawCircle(Color(0xFF2E7D32), r * 1.25f, Offset(centerX, centerY))
            drawCircle(Color(0xFF81C784), r * 1.05f, Offset(centerX, centerY))
            val arm = tile * 0.06f
            drawRect(
                Color(0xFFE8F5E9),
                Offset(centerX - arm / 2f, centerY - tile * 0.14f),
                Size(arm, tile * 0.28f),
            )
            drawRect(
                Color(0xFFE8F5E9),
                Offset(centerX - tile * 0.14f, centerY - arm / 2f),
                Size(tile * 0.28f, arm),
            )
        }

        PickupKind.Power -> {
            drawCircle(Color(0xFFBF360C), r * 1.2f, Offset(centerX, centerY))
            drawCircle(Color(0xFFFFAB40), r * 0.92f, Offset(centerX, centerY))
        }

        PickupKind.Bonus -> {
            drawCircle(Color(0xFFF9A825), r * 1.2f, Offset(centerX, centerY))
            drawCircle(Color(0xFFFFF9C4), r * 0.75f, Offset(centerX, centerY))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTank(
    centerX: Float,
    centerY: Float,
    tile: Float,
    body: Color,
    facing: Direction,
) {
    val w = tile * 0.72f
    val h = tile * 0.72f
    val left = centerX - w / 2f
    val top = centerY - h / 2f

    rotate(degrees = facingToDegrees(facing), pivot = Offset(centerX, centerY)) {
        drawRoundRect(
            color = body,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.18f, w * 0.18f),
        )

        val trackW = w * 0.16f
        drawRect(Color(0xFF111111), Offset(left, top), Size(trackW, h))
        drawRect(Color(0xFF111111), Offset(left + w - trackW, top), Size(trackW, h))

        val barrelLen = tile * 0.38f
        val barrelW = tile * 0.12f
        val bx = centerX - barrelW / 2f
        val by = top - barrelLen + tile * 0.06f
        drawRoundRect(
            color = Color(0xFF212121),
            topLeft = Offset(bx, by),
            size = Size(barrelW, barrelLen),
            cornerRadius = CornerRadius(barrelW * 0.35f, barrelW * 0.35f),
        )
    }
}

private fun facingToDegrees(f: Direction): Float =
    when (f) {
        Direction.Up -> 0f
        Direction.Right -> 90f
        Direction.Down -> 180f
        Direction.Left -> 270f
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawExplosionFx(
    center: Offset,
    tile: Float,
    fx: ExplosionFx,
) {
    val t = fx.t.coerceIn(0f, 1f)
    val fade = 1f - t

    when (fx.kind) {
        ExplosionKind.Spark -> {
            val r1 = tile * (0.1f + 0.38f * t)
            val r2 = tile * (0.06f + 0.18f * (1f - t))
            drawCircle(Color(0xFFFFE082).copy(alpha = fade * 0.88f), r1, center)
            drawCircle(Color(0xFFFF6F00).copy(alpha = fade * 0.62f), r2, center)
        }

        ExplosionKind.Tank -> {
            val alpha = fade * 0.92f
            val rOuter = tile * (0.28f + 0.92f * t)
            drawCircle(Color(0xFFFF9800).copy(alpha = alpha * 0.72f), rOuter, center)
            drawCircle(Color(0xFFFF5722).copy(alpha = alpha * 0.58f), rOuter * 0.62f, center)
            drawCircle(Color(0xFFB71C1C).copy(alpha = alpha * 0.42f), rOuter * 0.34f, center)

            val n = 10
            for (i in 0 until n) {
                val ang = i * 2f * PI.toFloat() / n
                val dist = tile * (0.22f + 0.88f * t)
                val px = center.x + cos(ang) * dist
                val py = center.y + sin(ang) * dist
                drawCircle(
                    Color(0xFF37474F).copy(alpha = fade * 0.85f),
                    radius = tile * (0.07f - 0.025f * t),
                    center = Offset(px, py),
                )
            }
        }

        ExplosionKind.Base -> {
            val pulse = tile * (0.4f + 1.05f * sin(t * PI.toFloat()))
            drawCircle(Color(0xFFFF1744).copy(alpha = fade * 0.58f), pulse, center)
            drawCircle(Color(0xFFFF9100).copy(alpha = fade * 0.48f), pulse * 0.68f, center)

            val sparks = 14
            for (i in 0 until sparks) {
                val ang = i * 2f * PI.toFloat() / sparks + t * 2.4f
                val dist = tile * (0.45f + 0.95f * t)
                drawCircle(
                    Color(0xFFFFEA00).copy(alpha = fade * 0.78f),
                    radius = tile * 0.075f,
                    center = Offset(center.x + cos(ang) * dist, center.y + sin(ang) * dist),
                )
            }
        }
    }
}
