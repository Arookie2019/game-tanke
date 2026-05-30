package com.aiprojects.tankbattle.game

import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

class GameEngine(
    private val campaign: List<Level> = LevelDefinitions.CAMPAIGN,
    private val random: Random = Random.Default,
) {
    init {
        require(campaign.isNotEmpty()) { "至少需要一关" }
    }

    /** 由 Android 层挂接 SoundPool 等，未设置则无声 */
    var onSfx: ((GameSfx) -> Unit)? = null

    private fun sfx(s: GameSfx) {
        onSfx?.invoke(s)
    }

    private var levelIndex: Int = 0

    val snapshot = mutableStateOf(createSnapshot(score = 0, lives = 3))

    var playerSteer: Direction? = null
    var playerWantsFire: Boolean = false

    private lateinit var grid: Array<Array<Tile>>
    private var cols: Int = 0
    private var rows: Int = 0

    private var spawnCooldown: Float = 0f
    private var nextBulletId: Int = 1
    private var nextTankId: Int = 10

    private val stuckTime = mutableMapOf<Int, Float>()
    private val aiTimer = mutableMapOf<Int, Float>()

    private var nextPickupId: Int = 1
    private var pickupSpawnTimer: Float = 4f

    fun restart() {
        levelIndex = 0
        stuckTime.clear()
        aiTimer.clear()
        spawnCooldown = 0.45f
        nextBulletId = 1
        nextTankId = 10
        nextPickupId = 1
        pickupSpawnTimer = 4f
        snapshot.value = createSnapshot(score = 0, lives = 3)
    }

    fun update(dt: Float) {
        updateExplosions(dt)
        if (snapshot.value.phase != Phase.Playing) return

        spawnCooldown = max(0f, spawnCooldown - dt)
        pickupSpawnTimer -= dt
        if (pickupSpawnTimer <= 0f) {
            pickupSpawnTimer = PICKUP_SPAWN_PERIOD_MIN + random.nextFloat() * PICKUP_SPAWN_PERIOD_RANGE
            if (random.nextFloat() < PICKUP_PERIOD_SPAWN_CHANCE) spawnRandomPickup()
        }

        movePlayer(dt)
        collectPickups()
        moveEnemies(dt)
        enemyBrains(dt)
        spawnEnemiesIfNeeded()
        updateBullets(dt)

        playerWantsFire = false
        syncTiles()
    }

    private fun updateExplosions(dt: Float) {
        val snap = snapshot.value
        if (snap.explosions.isEmpty()) return

        val next = snap.explosions.mapNotNull { ex ->
            val dur = explosionDuration(ex.kind)
            val nt = ex.t + dt / dur
            if (nt >= 1f) null else ex.copy(t = nt.coerceIn(0f, 1f))
        }
        snapshot.value = snap.copy(explosions = next)
    }

    private fun explosionDuration(kind: ExplosionKind): Float =
        when (kind) {
            ExplosionKind.Spark -> 0.2f
            ExplosionKind.Tank -> 0.42f
            ExplosionKind.Base -> 0.72f
        }

    private fun addExplosion(x: Float, y: Float, kind: ExplosionKind) {
        val snap = snapshot.value
        snapshot.value = snap.copy(explosions = snap.explosions + ExplosionFx(x, y, kind, 0f))
    }

    private fun movePlayer(dt: Float) {
        val snap = snapshot.value
        if (!snap.player.alive) return

        val p = snap.player
        val cooled = max(0f, p.cooldown - dt)
        val dir = playerSteer

        if (dir == null) {
            snapshot.value = snap.copy(player = p.copy(cooldown = cooled))
            tryPlayerFire()
            return
        }

        val facing = dir
        val (nx, ny) = tryMoveTank(
            p.x,
            p.y,
            facing.dx() * PLAYER_SPEED,
            facing.dy() * PLAYER_SPEED,
            dt,
            p.id,
        )

        snapshot.value = snapshot.value.copy(
            player = p.copy(x = nx, y = ny, facing = facing, cooldown = cooled),
        )
        tryPlayerFire()
    }

    private fun tryPlayerFire() {
        val snap = snapshot.value
        val p = snap.player
        if (!p.alive || p.cooldown > 0f || !playerWantsFire) return

        val spawn = findPlayerBulletSpawn(p) ?: return
        val (bx, by) = spawn

        snapshot.value = snap.copy(
            player = p.copy(cooldown = PLAYER_COOLDOWN),
            bullets = snap.bullets + Bullet(
                nextBulletId++,
                bx,
                by,
                p.facing,
                BulletOwner.Player,
                damage = p.attack,
            ),
        )
        sfx(GameSfx.PlayerFire)
    }

    private fun moveEnemies(dt: Float) {
        val snap = snapshot.value
        val enemies = snap.enemies.map { e ->
            if (!e.alive) return@map e

            val cooled = max(0f, e.cooldown - dt)
            val spd = ENEMY_SPEED * (e.enemyType?.speedMul ?: 1f)
            val (nx, ny) = tryMoveTank(
                e.x,
                e.y,
                e.facing.dx() * spd,
                e.facing.dy() * spd,
                dt,
                e.id,
            )

            val moved = nx != e.x || ny != e.y
            var t = e.copy(x = nx, y = ny, cooldown = cooled)

            if (!moved) {
                val st = (stuckTime[e.id] ?: 0f) + dt
                stuckTime[e.id] = st
                if (st > 0.35f) {
                    t = t.copy(facing = randomTurn(t.facing))
                    stuckTime[e.id] = 0f
                }
            } else {
                stuckTime[e.id] = 0f
            }
            t
        }
        snapshot.value = snap.copy(enemies = enemies)
    }

    private fun enemyBrains(dt: Float) {
        val snap = snapshot.value
        var bullets = snap.bullets

        val enemies = snap.enemies.map { e ->
            if (!e.alive) return@map e

            var t = e
            val timer = (aiTimer[e.id] ?: 0f) + dt
            if (timer > 0.55f + random.nextFloat() * 0.25f) {
                aiTimer[e.id] = 0f
                val roll = random.nextInt(10)
                if (roll < 4) {
                    val px = snapshot.value.player.x
                    val py = snapshot.value.player.y
                    t = t.copy(facing = biasTowardPlayer(t, px, py))
                } else if (roll < 7) {
                    t = t.copy(facing = randomTurn(t.facing))
                }
            } else {
                aiTimer[e.id] = timer
            }

            if (t.cooldown <= 0f && random.nextFloat() < 0.018f) {
                val bx = t.x + t.facing.dx() * SPAWN_DIST
                val by = t.y + t.facing.dy() * SPAWN_DIST
                if (bulletSpawnClear(bx, by)) {
                    bullets = bullets + Bullet(
                        nextBulletId++,
                        bx,
                        by,
                        t.facing,
                        BulletOwner.Enemy,
                        damage = t.attack,
                    )
                    t = t.copy(
                        cooldown = ENEMY_COOLDOWN_MIN + random.nextFloat() * (ENEMY_COOLDOWN_MAX - ENEMY_COOLDOWN_MIN),
                    )
                    sfx(GameSfx.EnemyFire)
                }
            }
            t
        }

        snapshot.value = snapshot.value.copy(enemies = enemies, bullets = bullets)
    }

    private fun biasTowardPlayer(t: Tank, px: Float, py: Float): Direction {
        val dx = px - t.x
        val dy = py - t.y
        return if (abs(dx) > abs(dy)) {
            if (dx > 0f) Direction.Right else Direction.Left
        } else {
            if (dy > 0f) Direction.Down else Direction.Up
        }
    }

    private fun randomTurn(d: Direction): Direction {
        val opts = Direction.entries.filter { it != d }
        return opts[random.nextInt(opts.size)]
    }

    private fun spawnEnemiesIfNeeded() {
        val snap = snapshot.value
        if (snap.enemiesRemaining <= 0) return
        if (snap.enemies.count { it.alive } >= MAX_ON_FIELD) return
        if (spawnCooldown > 0f) return

        val pts = snap.spawnPoints
        if (pts.isEmpty()) return

        val spawn = pts[random.nextInt(pts.size)]
        if (!tankSpawnClear(spawn.first, spawn.second)) return

        val et = randomEnemyTankType(random)
        val e = Tank(
            id = nextTankId++,
            x = spawn.first,
            y = spawn.second,
            facing = Direction.Down,
            cooldown = 0.85f,
            alive = true,
            hp = et.maxHp,
            maxHp = et.maxHp,
            attack = et.attack,
            enemyType = et,
        )

        snapshot.value = snap.copy(
            enemies = snap.enemies + e,
            enemiesRemaining = snap.enemiesRemaining - 1,
        )
        spawnCooldown = SPAWN_INTERVAL
    }

    private fun updateBullets(dt: Float) {
        data class Step(val bullet: Bullet, val nx: Float, val ny: Float, var cancelled: Boolean = false)

        val incoming = snapshot.value.bullets
        val moved = mutableListOf<Step>()

        for (b in incoming) {
            val nx = b.x + b.facing.dx() * BULLET_SPEED * dt
            val ny = b.y + b.facing.dy() * BULLET_SPEED * dt

            if (nx < BULLET_HALF || ny < BULLET_HALF || nx > cols - BULLET_HALF || ny > rows - BULLET_HALF) {
                continue
            }
            moved.add(Step(bullet = b, nx = nx, ny = ny))
        }

        for (i in moved.indices) {
            if (moved[i].cancelled) continue
            for (j in i + 1 until moved.size) {
                if (moved[j].cancelled) continue
                val oi = moved[i].bullet.owner
                val oj = moved[j].bullet.owner
                if (oi == oj) continue
                if (!bulletQuadsOverlap(moved[i].nx, moved[i].ny, moved[j].nx, moved[j].ny)) continue
                moved[i].cancelled = true
                moved[j].cancelled = true
                addExplosion(
                    (moved[i].nx + moved[j].nx) / 2f,
                    (moved[i].ny + moved[j].ny) / 2f,
                    ExplosionKind.Spark,
                )
                sfx(GameSfx.Impact)
            }
        }

        val out = mutableListOf<Bullet>()
        for (m in moved) {
            if (m.cancelled) continue

            when (val wall = resolveWallForBullet(m.nx, m.ny)) {
                WallResult.BaseHit -> {
                    sfx(GameSfx.GameOver)
                    snapshot.value = snapshot.value.copy(
                        phase = Phase.Lost,
                        bullets = emptyList(),
                        pickups = emptyList(),
                    )
                    return
                }

                WallResult.Block -> continue
                WallResult.Pass -> Unit
            }

            if (resolveTankHits(m.nx, m.ny, m.bullet)) continue

            out.add(
                Bullet(
                    m.bullet.id,
                    m.nx,
                    m.ny,
                    m.bullet.facing,
                    m.bullet.owner,
                    m.bullet.damage,
                ),
            )
        }

        snapshot.value = snapshot.value.copy(bullets = out)
    }

    private fun bulletQuadsOverlap(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val qa = Quad(ax - BULLET_HALF, ay - BULLET_HALF, ax + BULLET_HALF, ay + BULLET_HALF)
        val qb = Quad(bx - BULLET_HALF, by - BULLET_HALF, bx + BULLET_HALF, by + BULLET_HALF)
        return quadOverlap(qa, qb)
    }

    private enum class WallResult {
        Pass,
        Block,
        BaseHit,
    }

    private fun resolveWallForBullet(nx: Float, ny: Float): WallResult {
        val tcx = nx.toInt().coerceIn(0, cols - 1)
        val tcy = ny.toInt().coerceIn(0, rows - 1)
        val cx = tcx + 0.5f
        val cy = tcy + 0.5f

        return when (grid[tcy][tcx]) {
            Tile.Empty -> WallResult.Pass
            Tile.Steel -> {
                sfx(GameSfx.Impact)
                addExplosion(cx, cy, ExplosionKind.Spark)
                WallResult.Block
            }

            Tile.Brick -> {
                sfx(GameSfx.Impact)
                grid[tcy][tcx] = Tile.Empty
                addExplosion(cx, cy, ExplosionKind.Spark)
                WallResult.Block
            }

            Tile.Base -> {
                sfx(GameSfx.BaseHit)
                addExplosion(cx, cy, ExplosionKind.Base)
                WallResult.BaseHit
            }
        }
    }

    private fun resolveTankHits(nx: Float, ny: Float, bullet: Bullet): Boolean {
        val q = Quad(nx - BULLET_HALF, ny - BULLET_HALF, nx + BULLET_HALF, ny + BULLET_HALF)

        if (bullet.owner == BulletOwner.Player) {
            for (e in snapshot.value.enemies) {
                if (!e.alive) continue
                if (quadOverlapsTank(q, e.x, e.y)) {
                    damageEnemy(e.id, bullet.damage)
                    return true
                }
            }
        } else {
            val p = snapshot.value.player
            if (p.alive && quadOverlapsTank(q, p.x, p.y)) {
                damagePlayerFromBullet(bullet.damage)
                return true
            }
        }
        return false
    }

    private fun damageEnemy(id: Int, damage: Int) {
        val cur = snapshot.value
        val enemy = cur.enemies.find { it.id == id && it.alive } ?: return
        val nh = enemy.hp - damage
        if (nh <= 0) {
            sfx(GameSfx.Explosion)
            addExplosion(enemy.x, enemy.y, ExplosionKind.Tank)
            val killScore = enemy.enemyType?.killScore ?: 100
            val enemies = cur.enemies.map {
                if (it.id == id) it.copy(alive = false, hp = 0) else it
            }
            snapshot.value = cur.copy(enemies = enemies, score = cur.score + killScore)
            if (random.nextFloat() < PICKUP_DROP_ON_KILL_CHANCE) spawnRandomPickup()
            checkWaveComplete()
        } else {
            sfx(GameSfx.Impact)
            snapshot.value = cur.copy(
                enemies = cur.enemies.map { if (it.id == id) it.copy(hp = nh) else it },
            )
        }
    }

    private fun checkWaveComplete() {
        val snap = snapshot.value
        val aliveEnemies = snap.enemies.count { it.alive }
        if (aliveEnemies == 0 && snap.enemiesRemaining == 0) {
            advanceCampaignOrComplete()
        }
    }

    private fun advanceCampaignOrComplete() {
        if (levelIndex >= campaign.lastIndex) {
            sfx(GameSfx.LevelWin)
            snapshot.value = snapshot.value.copy(phase = Phase.Won)
            return
        }

        val keepScore = snapshot.value.score
        val keepLives = snapshot.value.lives
        val carryExplosions = snapshot.value.explosions
        levelIndex++
        resetStageState()
        snapshot.value = createSnapshot(score = keepScore, lives = keepLives).copy(explosions = carryExplosions)
    }

    /** 进入下一关前清空坦克 AI 状态与子弹 ID 等（分数与生命由调用方传入）。 */
    private fun resetStageState() {
        stuckTime.clear()
        aiTimer.clear()
        spawnCooldown = 0.45f
        nextBulletId = 1
        nextTankId = 10
        nextPickupId = 1
        pickupSpawnTimer = 4f
    }

    private fun damagePlayerFromBullet(damage: Int) {
        val cur = snapshot.value
        val p = cur.player
        if (!p.alive) return

        val nh = p.hp - damage
        if (nh <= 0) {
            sfx(GameSfx.PlayerHit)
            addExplosion(p.x, p.y, ExplosionKind.Tank)
            val lives = cur.lives - 1
            if (lives <= 0) {
                sfx(GameSfx.GameOver)
                snapshot.value = cur.copy(
                    lives = 0,
                    phase = Phase.Lost,
                    player = p.copy(alive = false, hp = 0),
                    pickups = emptyList(),
                )
                return
            }
            val (sx, sy) = cur.playerSpawn
            snapshot.value = cur.copy(
                lives = lives,
                player = p.copy(
                    x = sx,
                    y = sy,
                    alive = true,
                    cooldown = PLAYER_COOLDOWN,
                    hp = PLAYER_BASE_HP,
                    maxHp = PLAYER_BASE_HP,
                    attack = PLAYER_BASE_ATTACK,
                ),
                bullets = cur.bullets.filter { it.owner != BulletOwner.Enemy },
            )
        } else {
            sfx(GameSfx.Impact)
            addExplosion(p.x, p.y, ExplosionKind.Spark)
            snapshot.value = cur.copy(player = p.copy(hp = nh))
        }
    }

    private fun collectPickups() {
        val snap = snapshot.value
        if (!snap.player.alive || snap.phase != Phase.Playing) return

        val pq = tankQuad(snap.player.x, snap.player.y)
        var player = snap.player
        var score = snap.score
        val remaining = mutableListOf<Pickup>()
        var picked = false
        for (pk in snap.pickups) {
            if (quadOverlap(pq, pickupQuad(pk.x, pk.y))) {
                val r = applyPickupKind(pk.kind, player, score)
                player = r.first
                score = r.second
                picked = true
                sfx(GameSfx.Impact)
            } else {
                remaining.add(pk)
            }
        }
        if (picked) {
            snapshot.value = snap.copy(player = player, score = score, pickups = remaining)
        }
    }

    private fun applyPickupKind(kind: PickupKind, p: Tank, score: Int): Pair<Tank, Int> =
        when (kind) {
            PickupKind.Repair -> p.copy(hp = minOf(p.maxHp, p.hp + 1)) to score
            PickupKind.Power -> p.copy(attack = minOf(PLAYER_ATTACK_CAP, p.attack + 1)) to score
            PickupKind.Bonus -> p to score + BONUS_PICKUP_SCORE
        }

    private fun spawnRandomPickup() {
        val snap = snapshot.value
        if (snap.phase != Phase.Playing) return

        val cells = mutableListOf<Pair<Int, Int>>()
        for (ty in 1 until rows - 1) {
            for (tx in 1 until cols - 1) {
                if (grid[ty][tx] != Tile.Empty) continue
                val cx = tx + 0.5f
                val cy = ty + 0.5f
                if (!pickupSpawnClear(cx, cy)) continue
                cells.add(tx to ty)
            }
        }
        if (cells.isEmpty()) return

        val (tx, ty) = cells[random.nextInt(cells.size)]
        val kind = when (random.nextInt(3)) {
            0 -> PickupKind.Repair
            1 -> PickupKind.Power
            else -> PickupKind.Bonus
        }
        val pk = Pickup(nextPickupId++, tx + 0.5f, ty + 0.5f, kind)
        snapshot.value = snap.copy(pickups = snap.pickups + pk)
    }

    private fun pickupSpawnClear(cx: Float, cy: Float): Boolean {
        val pq = pickupQuad(cx, cy)
        val snap = snapshot.value
        if (quadOverlap(pq, tankQuad(snap.player.x, snap.player.y))) return false
        for (e in snap.enemies) {
            if (!e.alive) continue
            if (quadOverlap(pq, tankQuad(e.x, e.y))) return false
        }
        for (p in snap.pickups) {
            if (hypot((p.x - cx).toDouble(), (p.y - cy).toDouble()) < 0.4f) return false
        }
        return true
    }

    private fun pickupQuad(cx: Float, cy: Float): Quad =
        Quad(cx - PICKUP_HALF, cy - PICKUP_HALF, cx + PICKUP_HALF, cy + PICKUP_HALF)

    private fun tryMoveTank(x: Float, y: Float, vx: Float, vy: Float, dt: Float, selfId: Int): Pair<Float, Float> {
        val dx = vx * dt
        val dy = vy * dt

        var fx = x
        var fy = y

        val qx = tankQuad(x + dx, y)
        if (!quadHitsSolid(qx) && !quadHitsTanks(qx, selfId)) fx = x + dx

        val qy = tankQuad(x, y + dy)
        if (!quadHitsSolid(qy) && !quadHitsTanks(qy, selfId)) fy = y + dy

        fx = fx.coerceIn(TANK_HALF, cols - TANK_HALF)
        fy = fy.coerceIn(TANK_HALF, rows - TANK_HALF)
        return Pair(fx, fy)
    }

    private fun quadHitsSolid(q: Quad): Boolean {
        val minX = floor(q.left).toInt().coerceIn(0, cols - 1)
        val maxX = floor(q.right - 1e-4f).toInt().coerceIn(0, cols - 1)
        val minY = floor(q.top).toInt().coerceIn(0, rows - 1)
        val maxY = floor(q.bottom - 1e-4f).toInt().coerceIn(0, rows - 1)
        for (ty in minY..maxY) {
            for (tx in minX..maxX) {
                if (solid(grid[ty][tx])) {
                    val tile = Quad(tx.toFloat(), ty.toFloat(), (tx + 1).toFloat(), (ty + 1).toFloat())
                    if (quadOverlap(q, tile)) return true
                }
            }
        }
        return false
    }

    private fun quadHitsTanks(q: Quad, selfId: Int): Boolean {
        val snap = snapshot.value
        if (snap.player.alive && snap.player.id != selfId) {
            if (quadOverlap(q, tankQuad(snap.player.x, snap.player.y))) return true
        }
        for (e in snap.enemies) {
            if (!e.alive || e.id == selfId) continue
            if (quadOverlap(q, tankQuad(e.x, e.y))) return true
        }
        return false
    }

    private fun bulletSpawnClear(cx: Float, cy: Float): Boolean {
        val q = Quad(cx - BULLET_HALF, cy - BULLET_HALF, cx + BULLET_HALF, cy + BULLET_HALF)
        return !quadHitsSolid(q) && !quadHitsTanks(q, -1)
    }

    /** 玩家子弹：只避免穿进墙体/基地与自己的车身；可与敌车重叠以便贴脸开火 */
    private fun playerBulletSpawnClear(cx: Float, cy: Float, self: Tank): Boolean {
        if (cx - BULLET_HALF < 0f || cy - BULLET_HALF < 0f ||
            cx + BULLET_HALF > cols.toFloat() || cy + BULLET_HALF > rows.toFloat()
        ) {
            return false
        }
        val q = Quad(cx - BULLET_HALF, cy - BULLET_HALF, cx + BULLET_HALF, cy + BULLET_HALF)
        if (quadHitsSolid(q)) return false
        if (quadOverlap(q, tankQuad(self.x, self.y))) return false
        return true
    }

    /** 沿炮口尝试多档距离：贴墙时略短的出射点仍可合法 */
    private fun findPlayerBulletSpawn(p: Tank): Pair<Float, Float>? {
        for (d in PLAYER_BULLET_SPAWN_TRIES) {
            val bx = p.x + p.facing.dx() * d
            val by = p.y + p.facing.dy() * d
            if (playerBulletSpawnClear(bx, by, p)) return bx to by
        }
        return null
    }

    private fun tankSpawnClear(cx: Float, cy: Float): Boolean {
        val q = tankQuad(cx, cy)
        if (quadHitsSolid(q)) return false
        val snap = snapshot.value
        if (snap.player.alive && quadOverlap(q, tankQuad(snap.player.x, snap.player.y))) return false
        for (e in snap.enemies) {
            if (!e.alive) continue
            if (quadOverlap(q, tankQuad(e.x, e.y))) return false
        }
        return true
    }

    private fun quadOverlapsTank(q: Quad, tx: Float, ty: Float): Boolean =
        quadOverlap(q, tankQuad(tx, ty))

    private fun syncTiles() {
        val s = snapshot.value
        snapshot.value = s.copy(tiles = grid.map { row -> row.toList() })
    }

    private fun solid(t: Tile): Boolean =
        when (t) {
            Tile.Empty -> false
            Tile.Brick, Tile.Steel, Tile.Base -> true
        }

    private fun createSnapshot(score: Int, lives: Int): GameSnapshot {
        val level = campaign[levelIndex]
        val lines = level.lines
        rows = lines.size
        cols = lines.first().length

        grid = Array(rows) { r ->
            Array(cols) { c ->
                when (lines[r][c]) {
                    '#' -> Tile.Brick
                    '@' -> Tile.Steel
                    'B' -> Tile.Base
                    else -> Tile.Empty
                }
            }
        }

        var playerSpawn = Pair(cols / 2f, rows - 2.5f)
        val spawns = mutableListOf<Pair<Float, Float>>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                when (lines[r][c]) {
                    'P' -> {
                        playerSpawn = Pair(c + 0.5f, r + 0.5f)
                        grid[r][c] = Tile.Empty
                    }

                    'E' -> {
                        spawns.add(Pair(c + 0.5f, r + 0.5f))
                        grid[r][c] = Tile.Empty
                    }

                    else -> Unit
                }
            }
        }

        val tiles = grid.map { row -> row.toList() }

        val player = Tank(
            id = 1,
            x = playerSpawn.first,
            y = playerSpawn.second,
            facing = Direction.Up,
            cooldown = 0f,
            alive = true,
            hp = PLAYER_BASE_HP,
            maxHp = PLAYER_BASE_HP,
            attack = PLAYER_BASE_ATTACK,
            enemyType = null,
        )

        return GameSnapshot(
            tiles = tiles,
            cols = cols,
            rows = rows,
            player = player,
            enemies = emptyList(),
            bullets = emptyList(),
            pickups = emptyList(),
            explosions = emptyList(),
            lives = lives,
            score = score,
            enemiesRemaining = level.enemiesInLevel,
            phase = Phase.Playing,
            spawnPoints = spawns.ifEmpty {
                listOf(Pair(cols * 0.25f, 2.5f), Pair(cols * 0.75f, 2.5f))
            },
            playerSpawn = playerSpawn,
            levelDisplayIndex = levelIndex + 1,
            totalLevels = campaign.size,
            levelTitle = level.title,
        )
    }

    private data class Quad(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun tankQuad(cx: Float, cy: Float): Quad =
        Quad(cx - TANK_HALF, cy - TANK_HALF, cx + TANK_HALF, cy + TANK_HALF)

    private fun quadOverlap(a: Quad, b: Quad): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    companion object {
        private const val TANK_HALF = 0.36f
        private const val BULLET_HALF = 0.08f
        private const val PLAYER_SPEED = 3.1f
        private const val ENEMY_SPEED = 2.0f
        private const val BULLET_SPEED = 11.5f
        private const val PLAYER_COOLDOWN = 0.55f
        private const val ENEMY_COOLDOWN_MIN = 0.75f
        private const val ENEMY_COOLDOWN_MAX = 1.9f
        private const val MAX_ON_FIELD = 4
        private const val SPAWN_INTERVAL = 2.2f
        private const val SPAWN_DIST = 0.52f

        private const val PLAYER_BASE_HP = 3
        private const val PLAYER_BASE_ATTACK = 1
        private const val PLAYER_ATTACK_CAP = 5
        private const val BONUS_PICKUP_SCORE = 95
        private const val PICKUP_HALF = 0.14f
        private const val PICKUP_DROP_ON_KILL_CHANCE = 0.22f
        private const val PICKUP_PERIOD_SPAWN_CHANCE = 0.42f
        private const val PICKUP_SPAWN_PERIOD_MIN = 9f
        private const val PICKUP_SPAWN_PERIOD_RANGE = 7f

        /** 玩家寻位出射点（由远及近），解决贴墙 / 贴敌车原逻辑整段放空 */
        private val PLAYER_BULLET_SPAWN_TRIES = floatArrayOf(
            SPAWN_DIST,
            0.46f,
            0.42f,
            0.38f,
            0.34f,
            0.30f,
            0.26f,
        )
    }
}
